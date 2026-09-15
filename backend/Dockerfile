FROM node:22-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

# Populated by CI so a running revision is traceable back to a commit.
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.title="roxstar-backend" \
      org.opencontainers.image.description="Roxstar voice draft, room and spin wheel backend"

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=builder /app/dist ./dist

USER node

# Documentation only. The process binds whatever PORT is injected, which is how
# Cloud Run (PORT=8080) works without changing the image.
EXPOSE 3000

# Targets /ready, not /health: Docker exposes a single health signal and uses it to
# decide whether the container can serve traffic, which is a readiness question.
# /health (liveness) is used by the Cloud Run liveness probe instead.
# The port is read from the environment so the check follows PORT rather than
# assuming 3000.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "const p=process.env.PORT||3000;fetch('http://localhost:'+p+'/ready').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "dist/server.js"]
