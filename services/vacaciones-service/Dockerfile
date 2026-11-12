# Fase 7 (E01/E02): imagen multi-stage, base fijada y usuario no root.

# Etapa 1: dependencias de producción (sin devDependencies). npm y su caché
# se quedan en esta etapa.
FROM node:22.21.1-alpine3.22 AS deps
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# Etapa 2: runtime. Solo node, curl, node_modules de producción, migraciones y src/.
FROM node:22.21.1-alpine3.22 AS runtime

# Fase 9 (seguridad de imagen): la base está fijada por versión, pero sus paquetes reciben
# parches después de publicada. Se aplican los de seguridad para que Trivy no encuentre
# vulnerabilidades CRITICAL ya corregidas (OpenSSL, GnuTLS...).
RUN apk upgrade --no-cache

LABEL org.opencontainers.image.title="vacaciones-service" \
      org.opencontainers.image.description="Períodos de vacaciones (Node.js 22 + Express + PostgreSQL)" \
      org.opencontainers.image.source="https://github.com/juan-contrerasM/reto1-microservicios"

# curl: lo usa el healthcheck de docker-compose.yml raíz contra GET /health.
# npm, npx, corepack y yarn no se usan en runtime (las migraciones corren con
# la API programática de node-pg-migrate desde src/index.js). UID/GID 10001.
RUN apk add --no-cache curl \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
              /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
              /usr/local/bin/yarn /usr/local/bin/yarnpkg /opt/yarn-* \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app

WORKDIR /app
# Código, migraciones y dependencias con dueño root (solo lectura para el proceso).
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY migrations ./migrations
COPY src ./src

ENV PORT=8085
EXPOSE 8085

USER 10001:10001
CMD ["node", "--import", "./src/telemetria.js", "src/index.js"]
