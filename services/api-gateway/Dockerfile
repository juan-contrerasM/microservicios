FROM node:22-alpine
WORKDIR /app

# curl: lo usará el healthcheck de docker-compose.yml raíz contra GET /health (Etapa 2).
RUN apk add --no-cache curl

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src

ENV PORT=8080
EXPOSE 8080

USER node
CMD ["node", "src/index.js"]
