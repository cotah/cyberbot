"""Tests for the health endpoint."""

from fastapi.testclient import TestClient

from app.main import app


def test_health_returns_200() -> None:
    """The health endpoint should always respond with HTTP 200."""
    with TestClient(app) as client:
        response = client.get("/api/health")
    assert response.status_code == 200


def test_health_response_shape() -> None:
    """The health payload must contain the required fields."""
    with TestClient(app) as client:
        response = client.get("/api/health")

    body = response.json()
    assert set(body.keys()) >= {"status", "version", "services", "environment"}
    assert body["status"] in {"ok", "degraded"}
    assert isinstance(body["services"], dict)
    assert "backend" in body["services"]
    assert body["services"]["backend"] == "ok"
