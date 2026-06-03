FROM ghcr.io/astral-sh/uv:latest AS uv
FROM python:3.11-slim

COPY --from=uv /uv /uvx /usr/local/bin/

WORKDIR /app

COPY pyproject.toml ./
COPY app ./app

RUN uv sync --no-dev

ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8000

ENTRYPOINT []
CMD sh -c "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"
