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
# KUBE-6: shipped as a TEMPLATE — the entrypoint's envsubst step renders
# it to /etc/nginx/conf.d/default.conf, filling ${DNS_RESOLVER} (see
# resolverEnv.envsh: docker's 127.0.0.11 vs Kubernetes' CoreDNS, both
# derived from /etc/resolv.conf at start). Only defined env vars are
# substituted, so nginx's own $lowercase variables pass through.
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --chmod=0755 resolverEnv.envsh /docker-entrypoint.d/05-resolverEnv.envsh

COPY --from=build /build/dist /usr/share/nginx/html

# nginx:alpine ships without the curl that our compose healthcheck uses
# elsewhere; wget IS in BusyBox so we rely on that.
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=12 --start-period=10s \
  CMD wget -qO- http://localhost/healthz || exit 1
