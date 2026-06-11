# ---- Build stage ----
# SECURITY S-14 — base images pinned to @sha256 digests (tag kept for
# readability). Refresh with: docker buildx imagetools inspect <tag>.
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS build

WORKDIR /build

COPY package.json package-lock.json* ./
# `npm ci` requires lock file. For the skeleton we ship without one, so
# fall through to `npm install` if no lock is present.
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

COPY tsconfig.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src
RUN npm run build

# ---- Runtime stage ----
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

LABEL org.opencontainers.image.title="jmeter-cloud-ui" \
      org.opencontainers.image.description="React + Vite control plane for jmeter-cloud"

# Default nginx config from the runtime image already serves /usr/share/nginx/html.
# Replace with our own to add the /api/* reverse-proxy + SPA fallback.
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /build/dist /usr/share/nginx/html

# nginx:alpine ships without the curl that our compose healthcheck uses
# elsewhere; wget IS in BusyBox so we rely on that.
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=12 --start-period=10s \
  CMD wget -qO- http://localhost/healthz || exit 1
