# Fase 7 (E01/E02): imagen multi-stage, base fijada y usuario no root.

# Etapa 1: dependencias de producción. npm y su caché se quedan en esta etapa.
FROM node:22.21.1-alpine3.22 AS deps
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# Etapa 2: runtime. Solo node, curl, node_modules de producción y src/.
FROM node:22.21.1-alpine3.22 AS runtime

LABEL org.opencontainers.image.title="api-gateway" \
      org.opencontainers.image.description="API Gateway del monorepo (Node.js 22 + Express)" \
      org.opencontainers.image.source="https://github.com/juan-contrerasM/reto1-microservicios"

# curl: lo usa el healthcheck de docker-compose.yml raíz contra GET /health.
# npm, npx, corepack y yarn no se usan en runtime: se eliminan para reducir
# tamaño y superficie de vulnerabilidades. UID/GID fijos 10001.
RUN apk add --no-cache curl \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
              /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
              /usr/local/bin/yarn /usr/local/bin/yarnpkg /opt/yarn-* \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app

WORKDIR /app
# Código y dependencias quedan con dueño root (solo lectura para el proceso).
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src

ENV PORT=8080
EXPOSE 8080

USER 10001:10001
CMD ["node", "--import", "./src/telemetria.js", "src/index.js"]
