# syntax=docker/dockerfile:1
FROM python:3.11-slim

# Install uv (fast Python package manager) from the official image.
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /usr/local/bin/

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app

# Install dependencies first (better layer caching).
COPY pyproject.toml ./
RUN uv sync --no-dev

# Copy application source.
COPY app ./app

# Use the virtualenv created by uv.
ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
