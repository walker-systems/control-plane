# Security model

Control Plane is a demo, but its security is built like it matters. This
page describes the model end to end — including its limitations, because
a security writeup that omits the trade-offs isn't one.

**Try it live**: the login page has two one-click personas —
*Explore the demo* signs in as an OPERATOR (all jobs, audit trails),
*view as a restricted user* signs in as a plain USER (own jobs only, no
audit). The difference between those two sessions is this page in
action.

## Authentication

- **JWT access tokens, 15-minute lifetime.** HMAC-signed
  (`JWT_SECRET`, ≥32 bytes), carrying the user id as `sub` and the
  role list as a `roles` claim. Stateless: the API validates the
  signature, never a session store.
- **Refresh tokens: opaque, hashed, rotated.** A refresh token is 32
  random bytes; the database stores only its SHA-256 hash — a DB leak
  exposes nothing replayable. Every refresh **rotates** the token
  (old one revoked, new one issued), so a stolen refresh token dies
  the moment either party uses it. Logout revokes server-side.
- **Passwords: BCrypt**, with a length-only policy (minimum 12
  characters) per NIST SP 800-63B — composition rules push users
  toward predictable substitutions; length is what costs attackers.
- **Account status is enforced at every token issue point.** LOCKED
  and DISABLED accounts are rejected at login *and* refresh with a
  distinct 403 (`ACCOUNT_LOCKED` / `ACCOUNT_DISABLED`) — deliberately
  distinguishable from 401 invalid-credentials, because the caller
  proved who they are; the account is what's blocked.

## Authorization

Three roles, enforced in the **service layer** (not just controller
annotations), so the rules hold regardless of entry point:

| | USER | OPERATOR | ADMIN |
|---|---|---|---|
| Jobs & schedules | own only | all | all |
| Audit trail | — | read | read |
| User management | — | — | full |

- **The JWT `roles` claim is the single source of truth.** The UI
  decodes the same claim the API authorizes against, so the client
  never renders a button the server would 403. Role changes take
  effect at the next token refresh — the UI says so where it matters.
- **User management is admin-only, with no self-service
  registration.** Accounts exist because an ADMIN created one (or
  bootstrap seeded it). Admins cannot modify their own status or
  roles (409 `SELF_MODIFICATION`) — no locking yourself out, no
  dropping the last ADMIN by accident; a second admin can always act
  on the first.
- **Locking a user ends their sessions.** Every outstanding refresh
  token is revoked on LOCK/DISABLE; the account is dead at the next
  rotation. (A held access token rides out its ≤15 minutes — see
  limitations.)

## Audit

Every security-relevant transition writes an audit row: logins
(including failures, recorded in an independent transaction so the
row survives the request's rollback), token refreshes, logouts, user
creation, role and status changes, and the whole job/schedule
lifecycle. Rows carry actor, target, JSON metadata (e.g. role
before→after), client IP (X-Forwarded-For aware), and user agent.
Reading audit requires OPERATOR or ADMIN.

## Transport & infrastructure

- **TLS by default**: Caddy provisions and renews Let's Encrypt
  certificates; the `.dev` TLD is HSTS-preloaded in browsers, so an
  insecure connection to the public demo is not even attemptable.
- **Minimal exposure**: only Caddy binds host ports. Postgres, Redis,
  RabbitMQ, and the API — actuator endpoints included — exist only on
  the internal compose network. The proxy forwards `/api/*` and
  nothing else.
- **Hardened responses**: HSTS, `X-Content-Type-Options`,
  `X-Frame-Options: DENY`, `Referrer-Policy`, `Server` header
  stripped.
- **Containers**: the API runs as a non-root user; secrets arrive via
  environment with fail-fast `${VAR:?}` guards so a half-configured
  deployment refuses to boot rather than booting open.

## Known limitations (by design, at this scale)

- **Stateless access tokens can't be recalled.** Locking a user kills
  refresh, not the in-flight access token — worst case ≤15 minutes of
  residual access. The fix (a token denylist or short-TTL introspection
  cache in Redis) isn't warranted for a demo.
- **No login rate limiting.** Failed logins are audited (with IP) but
  not throttled. A real deployment would add a limiter at Caddy or on
  the auth endpoints.
- **Tokens live in `localStorage`.** Simpler than an httpOnly-cookie
  flow and fine for a demo; it does mean an XSS bug could read them.
  The CSP-hardened, cookie-based variant is the production upgrade.
- **The demo accounts are deliberately public.** That's the feature.
  Private deployments disable them by setting the bootstrap demo vars
  empty (see [Deployment](deployment.md)).
