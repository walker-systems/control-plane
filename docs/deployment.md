# Deployment

Control Plane deploys as five containers on a single droplet, driven by
GitHub Actions. Every push to `main` builds two images, pushes them to
GHCR, and rolls the server forward over SSH.

```
                    ┌──────────────────────────── droplet ─┐
  browser ──443──►  │  web (Caddy)                          │
                    │   ├─ serves the React SPA (static)    │
                    │   └─ /api/* ──► api (Spring Boot)     │
                    │                  ├─► postgres          │
                    │                  ├─► redis             │
                    │                  └─► rabbitmq          │
                    └───────────────────────────────────────┘
```

Only Caddy binds host ports (80/443). It terminates TLS with an
automatic Let's Encrypt certificate, serves the built UI, and reverse
proxies `/api/*` to the API container. Postgres, Redis, RabbitMQ, and
the API itself are reachable only on the internal compose network —
actuator endpoints included.

## Pieces

| Path | What it is |
|---|---|
| `services/api/Dockerfile` | Multi-stage build: JDK 25 builder → JRE runtime, non-root, healthcheck on `/actuator/health` |
| `services/ui/Dockerfile` | Node 22 builds the SPA → Caddy image serves it + proxies the API |
| `deploy/Caddyfile` | Caddy config baked into the web image (`DOMAIN` env at runtime) |
| `deploy/compose.prod.yml` | The five-service stack; fails fast if required env vars are unset |
| `deploy/.env.prod.example` | Template for the droplet's `.env` |
| `.github/workflows/deploy.yml` | Build → push to GHCR → SSH deploy |

## One-time server setup

A 2 GB droplet is the practical minimum (Java + Postgres + RabbitMQ +
Redis + Caddy; 1 GB swaps itself to death).

1. **Create the droplet** — Ubuntu 24.04 LTS, SSH key auth. Note the IP.

2. **DNS** — add an A record for your domain pointing at the droplet IP
   *before* first boot of the stack; Caddy needs it resolvable to pass
   the ACME challenge and issue the certificate.

3. **Install Docker** (as root):

   ```bash
   curl -fsSL https://get.docker.com | sh
   ```

4. **Create a deploy user** with docker rights and its own SSH key —
   the CI pipeline logs in as this user, so it shouldn't be root:

   ```bash
   adduser --disabled-password --gecos "" deploy
   usermod -aG docker deploy
   su - deploy -c 'mkdir -p ~/.ssh && chmod 700 ~/.ssh'
   # paste a public key generated for CI:
   #   ssh-keygen -t ed25519 -f ci-deploy -N ""     (run locally)
   su - deploy -c 'cat >> ~/.ssh/authorized_keys'   # paste ci-deploy.pub
   ```

5. **Stage the stack**:

   ```bash
   mkdir -p /opt/control-plane && chown deploy:deploy /opt/control-plane
   # as deploy:
   cd /opt/control-plane
   # copy deploy/compose.prod.yml here as compose.yml
   # copy deploy/.env.prod.example here as .env and fill it in
   ```

   Generate the JWT secret with `openssl rand -base64 48`. Compose
   refuses to start if any required value is missing (`:?` expansion),
   so a half-filled `.env` fails loudly instead of booting broken.

6. **Firewall** (optional but sensible): `ufw allow 22,80,443/tcp && ufw enable`.

7. **First start**:

   ```bash
   docker compose pull && docker compose up -d
   docker compose logs -f api   # watch Flyway migrate, bootstrap admin created
   ```

   The API creates the bootstrap admin on first boot (idempotent), and
   the executor is enabled in `compose.prod.yml` — the demo runs jobs
   out of the box.

## GitHub configuration

Repository → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `DEPLOY_HOST` | droplet IP or hostname |
| `DEPLOY_USER` | `deploy` |
| `DEPLOY_SSH_KEY` | contents of the `ci-deploy` *private* key |

Until these exist, the deploy job's SSH step no-ops (images still build
and push), so the pipeline is safe to merge before the server exists.

The two GHCR packages (`control-plane-api`, `control-plane-web`) must be
**public** (package → settings → visibility) — the droplet pulls
anonymously. Keep them private instead by adding a
`docker login ghcr.io` with a read-only PAT on the droplet.

## The demo account

`compose.prod.yml` bootstraps a shared demo user
(`demo@control-plane.dev`, OPERATOR role) whose credentials are baked
into the UI's "Explore the demo" button — that's what makes the public
demo one-click. To run a private deployment without it, set
`BOOTSTRAP_DEMO_EMAIL=` and `BOOTSTRAP_DEMO_PASSWORD=` (both empty) in
`.env`.

## Day-2 operations

- **Deploy**: push to `main`. Or Actions → Deploy → Run workflow. The
  workflow also re-syncs `compose.prod.yml` to the droplet, so compose
  changes ship with the code — only `.env` is managed by hand.
- **Roll back**: on the droplet, set `IMAGE_TAG=<old sha>` in `.env`,
  `docker compose up -d`. Every deploy also pushes a `:<git sha>` tag
  precisely so this is possible.
- **Logs**: `docker compose logs -f api` (or `web`).
- **DB backup**: `docker compose exec postgres pg_dump -U controlplane control_plane | gzip > backup.sql.gz`
- **Local smoke test of the prod images** (no DNS needed): set
  `DOMAIN=:80` in `.env`, then `docker compose up` and browse
  `http://localhost`.
