# Web image: the built React SPA served by Caddy, which also owns TLS
# and reverse-proxies /api/* to the api container (see
# deploy/Caddyfile). Build from the REPO ROOT so the Caddyfile in
# deploy/ is in context:
#
#   docker build -f services/ui/Dockerfile -t control-plane-web .

FROM node:22-alpine AS build
WORKDIR /app

# package manifests first so `npm ci` stays cached across source-only
# changes.
COPY services/ui/package.json services/ui/package-lock.json ./
RUN npm ci

COPY services/ui/ ./
# tsc -b && vite build → dist/ (three static assets + index.html).
RUN npm run build

FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv

# Caddy's default entrypoint runs the Caddyfile; DOMAIN comes from the
# environment at runtime (compose.prod.yml).
EXPOSE 80 443
