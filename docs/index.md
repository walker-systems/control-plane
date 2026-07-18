# Control Plane

A distributed job orchestration platform: Postgres-backed job queue
with priorities, retries, cron schedules, lease-based crash recovery,
and a live operations UI.

- **Live demo:** [control-plane.dev](https://control-plane.dev)
- **Source:** [github.com/walker-systems/control-plane](https://github.com/walker-systems/control-plane)

## Where to start

| | |
|---|---|
| [Architecture](architecture.md) | How the queue, executor, leases, and auth fit together |
| [API](api.md) | Endpoint reference |
| [Deployment](deployment.md) | The droplet + Caddy + GitHub Actions pipeline behind the live site |
| [Roadmap](roadmap.md) | What's built, what's next |

## Run it locally

```bash
git clone https://github.com/walker-systems/control-plane
cd control-plane
docker compose -f deploy/compose.demo.yml up -d
./scripts/seed-demo.sh
```

Open <http://localhost:8000> — `demo@control-plane.dev` / `demo-password`.
