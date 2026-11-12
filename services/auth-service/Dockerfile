# Fase 7 (E01/E02): imagen multi-stage, base fijada y usuario no root.

# Etapa 1: instala requirements.txt en un venv aislado. pip, su caché y
# cualquier artefacto de compilación se quedan en esta etapa.
FROM python:3.12.12-slim-bookworm AS build

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH

WORKDIR /build
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Etapa 2: runtime slim. Solo el venv, curl y el código.
FROM python:3.12.12-slim-bookworm AS runtime

# Fase 9 (seguridad de imagen): la base está fijada por versión, pero Debian publica parches de
# seguridad después (OpenSSL, GnuTLS, Perl). Se aplican para que Trivy no encuentre CRITICAL
# ya corregidas.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

LABEL org.opencontainers.image.title="auth-service" \
      org.opencontainers.image.description="Identidad y seguridad (Python 3.12 + FastAPI + PostgreSQL)" \
      org.opencontainers.image.source="https://github.com/juan-contrerasM/reto1-microservicios"

# curl: lo usa el healthcheck de docker-compose.yml raíz contra GET /health.
# Usuario de sistema con UID/GID fijos 10001, sin home ni shell de login.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home \
               --home-dir /nonexistent --shell /usr/sbin/nologin app

# El venv se copia en la misma ruta: sus scripts (alembic, uvicorn) apuntan a
# /opt/venv/bin/python, que enlaza al /usr/local/bin/python3.12 de la misma base.
COPY --from=build /opt/venv /opt/venv

WORKDIR /app
COPY alembic.ini .
COPY alembic ./alembic
COPY src ./src

# PYTHONDONTWRITEBYTECODE: /app es de root y solo lectura para el proceso; así
# Python no intenta escribir __pycache__.
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONPATH=/app/src \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8086
EXPOSE 8086

USER 10001:10001
CMD ["sh", "-c", "alembic upgrade head && uvicorn auth_service.main:app --host 0.0.0.0 --port ${PORT} --log-config /app/src/auth_service/logging.json"]
