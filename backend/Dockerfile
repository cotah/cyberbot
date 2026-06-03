FROM ghcr.io/astral-sh/uv:latest AS uv
FROM python:3.11-slim

COPY --from=uv /uv /uvx /usr/local/bin/

WORKDIR /app

COPY pyproject.toml ./
COPY app ./app
COPY start.sh ./

RUN uv sync --no-dev
RUN chmod +x start.sh

ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8000

CMD ["./start.sh"]
