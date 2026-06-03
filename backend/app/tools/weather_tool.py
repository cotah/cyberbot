"""Weather tool backed by the free Open-Meteo API (no API key required).

Two calls are made:
1. Geocoding to resolve a city name into latitude/longitude.
2. Forecast to fetch the current temperature, humidity and condition.
"""

from typing import Any

import httpx
from loguru import logger

GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

# WMO weather interpretation codes -> human readable conditions.
_WEATHER_CODES: dict[int, str] = {
    0: "Clear sky",
    1: "Mainly clear",
    2: "Partly cloudy",
    3: "Overcast",
    45: "Fog",
    48: "Depositing rime fog",
    51: "Light drizzle",
    53: "Moderate drizzle",
    55: "Dense drizzle",
    61: "Slight rain",
    63: "Moderate rain",
    65: "Heavy rain",
    71: "Slight snow",
    73: "Moderate snow",
    75: "Heavy snow",
    80: "Slight rain showers",
    81: "Moderate rain showers",
    82: "Violent rain showers",
    95: "Thunderstorm",
    96: "Thunderstorm with slight hail",
    99: "Thunderstorm with heavy hail",
}


def _describe_code(code: int) -> str:
    return _WEATHER_CODES.get(code, "Unknown")


async def get_weather(city: str = "Dublin") -> dict[str, Any]:
    """Return current weather for a city.

    Args:
        city: City name to look up (defaults to Dublin).

    Returns:
        A dict with ``city``, ``temperature`` (Celsius), ``condition`` and
        ``humidity`` (percent). On failure, returns a dict with an ``error``
        key so the model can relay the problem to the user.
    """
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            geo_response = await client.get(
                GEOCODING_URL,
                params={"name": city, "count": 1, "language": "en", "format": "json"},
            )
            geo_response.raise_for_status()
            geo_data = geo_response.json()
            results = geo_data.get("results")
            if not results:
                return {"error": f"City '{city}' not found"}

            location = results[0]
            latitude = location["latitude"]
            longitude = location["longitude"]
            resolved_name = location.get("name", city)

            forecast_response = await client.get(
                FORECAST_URL,
                params={
                    "latitude": latitude,
                    "longitude": longitude,
                    "current": "temperature_2m,relative_humidity_2m,weather_code",
                },
            )
            forecast_response.raise_for_status()
            current = forecast_response.json().get("current", {})

        return {
            "city": resolved_name,
            "temperature": current.get("temperature_2m"),
            "condition": _describe_code(int(current.get("weather_code", -1))),
            "humidity": current.get("relative_humidity_2m"),
        }
    except Exception as exc:  # noqa: BLE001
        logger.error("get_weather failed for {}: {}", city, exc)
        return {"error": f"Could not fetch weather for '{city}': {exc}"}


# Anthropic tool definition consumed by the Claude client.
WEATHER_TOOL: dict[str, Any] = {
    "name": "get_weather",
    "description": (
        "Get current weather and forecast for a city. Use when the user asks "
        "about weather, temperature, or climate."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "city": {
                "type": "string",
                "description": "Name of the city to get the weather for.",
            }
        },
        "required": ["city"],
    },
}
