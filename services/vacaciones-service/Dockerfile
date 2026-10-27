FROM node:22-alpine
WORKDIR /app

RUN apk add --no-cache curl

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY migrations ./migrations
COPY src ./src

ENV PORT=8085
EXPOSE 8085

USER node
CMD ["node", "src/index.js"]
