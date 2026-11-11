FROM python:3.12-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY alembic.ini .
COPY alembic ./alembic
COPY config ./config
COPY src ./src

ENV PYTHONPATH=/app/src
ENV PORT=8084
ENV NOTIFICACIONES_TEMPLATES_FILE=/app/config/templates.json
EXPOSE 8084

CMD ["sh", "-c", "alembic upgrade head && uvicorn notificaciones.main:app --host 0.0.0.0 --port ${PORT} --log-config /app/src/notificaciones/logging.json"]
