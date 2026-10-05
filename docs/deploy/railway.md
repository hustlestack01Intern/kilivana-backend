# Deploying to Railway

The image is built from `Dockerfile` and everything else is configured in the Railway
dashboard. There is no `railway.toml` in this repository, on purpose — see
[Why there is no railway.toml](#why-there-is-no-railwaytoml).

Follow the steps in order. The application refuses to start on the `prod` profile until
the required variables are set, so a half-configured service fails loudly and names every
variable that is missing rather than booting into a broken state.

---

## 1. Create the project and the service

1. **New Project → Deploy from GitHub repo** → select this repository.
2. Railway creates a service for the repo. Open it.
3. The service must deploy from the Dockerfile:
   - **Settings → Deploy → Build → Builder: `Dockerfile`**
   - **Settings → Deploy → Build → Dockerfile Path: `Dockerfile`**
   - Leave the Dockerfile context as the repository root.
4. **Settings → Deploy → Restart Policy**: `On Failure`. This is Railway's default;
   it is listed here only so you can see it was not changed.
   - `Max restarts: 10` is also the default.

## 2. Attach PostgreSQL and Redis

1. **+ New → Database → Add PostgreSQL**. Wait for it to provision.
2. **+ New → Database → Add Redis**. Wait for it to provision.
3. Click **Variables** on the *application* service and add these two reference
   variables. Railway injects the plugin's own value; you only name where to find it.

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` |
   | `REDIS_URL` | `${{Redis.REDIS_URL}}` |

   The application rewrites the `postgres://` form of `DATABASE_URL` into the
   `jdbc:postgresql://` form the driver needs, and lifts the credentials out of it.
   `REDIS_URL` is a `redis://` URI that Spring Boot parses into host, port and
   credentials. Neither needs a host/port pair.

## 3. Set the variables

**Settings → Variables → Raw Editor**. Paste this block, then edit the three marked
values. Railway values trump anything in a file, so nothing here can be overridden by
`application.yml`.

```
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=<paste output of: openssl rand -base64 32>
ADMIN_BOOTSTRAP_KEY=<paste output of: openssl rand -base64 24>
MEDIA_ROOT=/data/uploads
CORS_ALLOWED_ORIGINS=https://your-admin-domain.example
RAILWAY_RUN_UID=0
```

| Variable | Required | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | Also set inside the image as `ENV SPRING_PROFILES_ACTIVE=prod`; declaring it here is belt-and-braces. Activates the prod preflight and `forward-headers-strategy: none`, which trusts **no** client-nominated `X-Forwarded-For`. See [Client IPs](#client-ips). |
| `JWT_SECRET` | yes | HS256 signing key. **Must** be a fresh random value of at least 32 characters. Startup refuses any value copied from this repository's local tooling. Generate with `openssl rand -base64 32`. |
| `ADMIN_BOOTSTRAP_KEY` | yes | Gates `POST /api/v1/admins/signup`, the only way to create the first admin. A known value means anyone can claim that account. Generate with `openssl rand -base64 24`. |
| `MEDIA_ROOT` | yes | Uploaded inspection photos and site-visit evidence. Must match the volume mount path below. Unset, uploads go to the container filesystem and are lost on every redeploy. |
| `CORS_ALLOWED_ORIGINS` | yes | Comma-separated origins of the client apps. A browser client on any other origin gets no CORS headers. |
| `RAILWAY_RUN_UID` | yes | `0` makes the container's main process start as root so `docker/entrypoint.sh` can hand the volume to uid 1000 before exec'ing the JVM. See [Volumes](#volumes). |
| `DATABASE_URL` | yes | Reference to the PostgreSQL plugin, see step 2. |
| `REDIS_URL` | yes | Reference to the Redis plugin, see step 2. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | **yes** | `MAIL_HOST` is **required**: the `prod` preflight refuses to start without it, naming the variable, because password reset is the only account-recovery path. `MAIL_USERNAME`/`MAIL_PASSWORD` are required together unless the relay is deliberately open — a half-configured authenticated relay is called out as a separate violation. The reset token is never returned in the HTTP response on `prod`; `password-reset.simulate-delivery` is refused at startup. |
| `PASSWORD_RESET_URL` | recommended | Link embedded in the reset email. Default is `http://localhost:3000/reset-password`, which is wrong in production. |
| `TRUSTED_PROXIES` | **yes** | **There is no shipped default, deliberately.** `application-prod.yml` defaults `trust-forwarded-headers` to `true`, so an absent list is a **failed boot** — not a degraded one. Two guards refuse to assemble the context: `ProxyProperties.validate()` and the `DeploymentEnvironmentValidator`, which names this variable in the failure message. It previously shipped as loopback + RFC1918 + RFC4193 + link-local, which let every deployment boot while permanently refusing every request: a PaaS edge connects from a public address that no shipped range contains, so all four credential endpoints answered `503 CLIENT_IDENTITY_UNRESOLVED` to everyone while `/actuator/health/liveness` stayed green. Set it to the actual edge address or the narrowest CIDR containing it — see [Client IPs](#7-client-ips-and-how-to-confirm-they-are-per-client). |
| `OPENAPI_ENABLED` / `SWAGGER_ENABLED` | optional | Both default to `false`, so API docs are not served. Leave them off unless you intend `/api-docs` to be reachable. |

> Generate the two secrets locally, never in a shared doc:
> ```bash
> openssl rand -base64 32   # JWT_SECRET
> openssl rand -base64 24   # ADMIN_BOOTSTRAP_KEY
> ```

## 4. Attach the volume

Uploads must survive a redeploy. Without a volume they are written to the container
filesystem and discarded every time Railway replaces the container.

1. **+ New → Volume** on the *service*.
2. **Mount Path**: `/data/uploads` — it must equal `MEDIA_ROOT`.
3. **Size**: pick the smallest plan that fits your evidence volume. The size is set here
   in the dashboard, not in a file.

Three facts about Railway volumes that will otherwise cost you an afternoon:

- **Railway mounts volumes as root.** The mounted directory arrives owned by uid 0.
- **A mount shadows the image directory.** The `chown /data/uploads` in `Dockerfile`
  runs at build time and is completely irrelevant once a volume is attached at that
  path — the mount replaces the directory rather than merging with it.
- **`RAILWAY_RUN_UID=0` is therefore mandatory, not optional.** It is the only
  documented Railway knob that lets the container start as root, and starting as root is
  the only way `docker/entrypoint.sh` can chown the volume to `appuser` (uid 1000). The
  entrypoint then drops privileges itself: `exec su-exec appuser …` means the JVM itself
  runs as uid 1000, never as root. The window in which the process is root is exactly the
  chown. This is recorded, with its cost, in
  `docs/decisions/0001-railway-deploy-and-honest-money-path.md`.

## 5. Healthcheck

**Settings → Deploy → Healthcheck Path**: `/actuator/health/liveness`.

Two things worth knowing:

- Railway's healthcheck is a **deploy-time gate**. It is probed from the hostname
  `healthcheck.railway.app` while the deployment comes up; Railway does not keep polling
  it once the deployment is live. A probe that fails does not later take the service out
  of rotation.
- **Healthcheck Timeout: `300` (seconds) is the documented default.** Setting it
  explicitly changes nothing — it is not a tuned value, it is the value you would have
  got by leaving it alone. Do not read it as a deliberate configuration.
- Liveness only, not readiness. Readiness also covers Postgres and Redis, so pointing
  the healthcheck at readiness would fail the deploy whenever a dependency blips.

**Do not put a host allowlist in front of this path.** If a proxy, WAF, or
`CORS_ALLOWED_ORIGINS`-style allowlist is ever introduced, Railway probes arrive from
`healthcheck.railway.app` and every deploy will fail until that hostname is allowed.

## 6. Deploy

1. **Settings → Deploy → Deploy** (or push to the connected branch).
2. Watch the deploy logs. On a misconfiguration the application exits with a preflight
   report that lists every problem **it can see** at once, and the variable to set for each:

   ```
   Deployment preflight failed for the 'prod' profile (2 problems):
     - kilivana.security.jwt.secret looks like a local placeholder value: set JWT_SECRET to ...
     - RATE_LIMIT_BACKEND is 'memory': set RATE_LIMIT_BACKEND=redis. The memory backend keeps ...
   ```

   What "can see" excludes, and this matters when you are reading a failed deploy log. The
   preflight checks the *variables it is given*; it never dials anything. A wrong or
   unreachable `DATABASE_URL` and a missing `ADMIN_BOOTSTRAP_KEY` are not in that list:
   - **`DATABASE_URL` / `DB_URL`** fails earlier and louder. Flyway runs before the context
     is built, so a bad or unreachable Postgres aborts the deploy with a Flyway or Hikari
     error naming the host — you will never reach the preflight message at all.
   - **`ADMIN_BOOTSTRAP_KEY`** is checked for presence, length and whether this repository
     has published the value. It cannot be checked for correctness: a key that is fresh,
     long and unknown here is accepted even if the operator pasted the wrong one, and the
     symptom appears later as a 403 from `POST /api/v1/admins/signup`.
   - A dependency that starts and then stops answering is not a startup problem at all. The
     limiter fails closed with a 503, which is a runtime symptom, not a preflight finding.

   So a clean preflight means "no variable is obviously wrong", not "the deployment is
   correct". If a deploy fails with something other than the preflight report, read the
   first stack trace in the log: it is the real cause.

4. **On the first deploy, do the client-IP check in step 7 before you serve real traffic.**

3. `PORT` needs no configuration. Railway injects it at runtime and the application binds
   `${PORT:${SERVER_PORT:8080}}`. Do not add an `ARG PORT` or `ENV PORT` to the Dockerfile:
   Railway never provides it at build time, so an `ENV PORT=8080` baked into the image
   would be a lie that only shows up as a 502 "Application Failed to Respond".

## 7. Client IPs, and how to confirm they are per-client

> ### ⚠️ First deploy only: check this before you serve real traffic
>
> **This is the single most likely way this deployment breaks, and it has never been observed
> on a real Railway project.** Nothing in this repository can tell you whether Railway's edge
> proxy connects from an address inside the default `TRUSTED_PROXIES` list. If it does not, every
> request on the platform resolves to the same client IP, and the four credential limits
> (login 5/min, signup 5/min, forgot 3/min, admin 3/min) become **global budgets for all users
> combined** — a total, immediate login outage that looks like a rate-limit bug and is not one.
>
> **Do this:**
>
> 1. Deploy once with `TRUST_FORWARDED_HEADERS=false` **and** `TRUSTED_PROXIES` set to a placeholder that satisfies the preflight (any value narrower than `0.0.0.0/0`, e.g. `127.0.0.1/32`). Trust being off is what lets you read the real peer; the placeholder exists only because an empty list is refused at startup regardless of the trust flag.
> 2. Open the deploy logs and make one request to `POST /api/v1/auth/login` (a wrong password is
>    fine). Then search the log for:
>
>    ```
>    Rate-limit buckets would collapse onto one client address
>    ```
>
> 3. **That line present → the four credential endpoints are refusing every request with 503
>    `CLIENT_IDENTITY_UNRESOLVED`, and none of them is being rate limited at all.** This is a hard
>    outage, not a slow leak: the peer is being refused because proxy trust is on and the peer is
>    not one this deployment sanctions, so the socket peer is the edge and every caller behind it
>    would share one bucket per rule. Add the peer address it names to `TRUSTED_PROXIES` (or the
>    narrowest CIDR that contains it, if Railway hands out a range), redeploy, and the line
>    disappears and login works. Each distinct untrusted peer is named once, so more than one
>    address in the log means the edge fleet is spread wider than one entry.
>
>    ```
>    TRUSTED_PROXIES=127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7,fe80::/10,<the peer address from the log>
>    ```
>
> 4. **That line absent → the edge is correctly named and every client has its own bucket.** Leave
>    `TRUSTED_PROXIES` alone. Absence is a real answer here, but only because of the second check
>    below — read step 5 before concluding anything from it.
>
> 5. **Check that proxy trust is actually ON.** Search the log for:
>
>    ```
>    kilivana.security.proxy.trust-forwarded-headers is false
>    ```
>
>    That line present → `TRUST_FORWARDED_HEADERS` is off, the collapse warning can never fire in
>    that configuration, and its absence above means nothing at all. You are back to one bucket per
>    rule for the whole platform. Fix it by turning trust on and naming the edge — never by leaving
>    it off, which is what makes the collapse invisible.
>
> The lines are logged at most once per distinct peer (and once per process for the trust notice),
> so they will not flood the log. Neither ever contains the client's forwarded address — that value
> is attacker-controlled, and putting it in your deploy log would let anyone write text there.

`TRUSTED_PROXIES` must not be empty while `TRUST_FORWARDED_HEADERS=true` (the default). The
application refuses to start in that state rather than silently trusting no proxy, so a
misconfiguration is a failed deploy with a named cause, not a quiet collapse.

---

There is **no `FORWARD_HEADERS_STRATEGY` variable to set by default, and that is the point.**
The API keys its four credential-abuse rate limits on the client address, so anything that
honours `X-Forwarded-For` from an *untrusted* peer lets that peer nominate its own bucket — one
forged header per attempt and the login limit is worthless. `application-prod.yml` therefore
sets:

```yaml
server:
  forward-headers-strategy: none
```

`ClientIpResolver` then walks `X-Forwarded-For` right-to-left, but **only when the real socket
peer matches `trusted-proxies`**, and past every trusted hop. Which is why the check above is a
first-deploy step rather than a footnote: the header is honoured only under that condition, and
the condition has never been observed.

Consequences you should expect rather than debug:

- **If the edge is not named in `TRUSTED_PROXIES`, the four credential endpoints answer 503
  `CLIENT_IDENTITY_UNRESOLVED` to every caller.** `POST /api/v1/auth/login`, `/signup`,
  `/register`, `/forgot-password` and `/api/v1/admins/signup` are all refused, because the socket
  peer is the shared edge and the address is not one client. Every other route is unaffected. No
  bucket is consumed, so nothing is throttled and no one can spend anyone else's budget. This is
  the deliberate trade: a loud, total, diagnosable outage on those four endpoints in exchange for
  not letting one anonymous actor spend `login:5/min` for the entire platform — which is what
  happens if the request is bucketed on the peer instead. The fix is the `TRUSTED_PROXIES` edit
  above. Do not "fix" it with `FORWARD_HEADERS_STRATEGY=native` either: that re-enables the header
  for every peer, so any caller nominates its own bucket, and it restores the bypass exactly.
- **`TRUST_FORWARDED_HEADERS` is not a remedy for anything.** Turning it off makes every peer
  attributable again, which reinstates the shared bucket, and it silences the collapse warning
  because that branch returns before the diagnostic is reached. The first-deploy check above is
  only meaningful while trust is on, which is why step 5 checks it.
- **`scripts/run-dev.sh` sets `TRUSTED_PROXIES=127.0.0.1/32,::1/128`** for the *local* stack only,
  and sets `TRUST_FORWARDED_HEADERS=false` alongside it — so nothing forwarded is honoured
  locally and the loopback entries exist only to satisfy the preflight. Never copy a local value
  into a Railway variable.
- **If there is ever no platform proxy at all**, `TRUST_FORWARDED_HEADERS` must be `false` and the
  peer *is* the client. That is the one configuration in which an untrusted peer is legitimately
  attributable, and the service says so in the log once at startup so the state is never silent.
- **The durable fix needs infrastructure this deployment does not have**: terminate TLS at a
  proxy that *overwrites* `X-Forwarded-For`, then trust exactly that proxy's CIDR. When that
  day comes, run `ForwardedHeaderBucketSpoofingIntegrationTest` before enabling it — it asserts
  a forged header is still ignored and must stay green.

## 8. Staging and production are separate environments

Every Railway variable is scoped to exactly one environment. `staging` and `production`
are two independent values of the *same variable name*, not a shared setting.

Concretely, each environment needs:

- its own `JWT_SECRET` — generated separately, never copied between environments;
- its own `ADMIN_BOOTSTRAP_KEY`;
- its own PostgreSQL plugin and its own Redis plugin;
- its own volume, with the same `/data/uploads` mount path.

Switch an environment by promoting the deployment, not by editing variables in place.
Promoting a deployment that points at staging's database moves your production traffic
onto staging data, and nothing will warn you.

---

## Why there is no `railway.toml`

`railway.toml` was deleted. Four independent reasons, any one of which is sufficient:

1. **The volume section was never valid.** `[[volumes]]` is not in Railway's schema.
   `railway.toml` permits a single `deploy.requiredMountPath` string; volume attachment
   is a dashboard, CLI or GraphQL operation. The file advertised a mount that was never
   read.
2. **Config as Code has a hard removal date of 2026-12-01.** After that date
   `railway.toml` is silently not read — a deployment would look configured and be
   configured by nothing. New services cannot opt in at all.
3. **The file's remaining tuning was two documented defaults presented as decisions.**
   `healthcheckTimeout = 300` and `restartPolicyType = "ON_FAILURE"` with
   `restartPolicyMaxRetries = 10` are exactly what Railway already applies. Anyone
   reading the file would believe someone had thought about restart behaviour.
4. **Dashboard configuration does not rot.** It is visible, it is reversible, and it
   keeps working past the cutoff.

### If the team later wants infrastructure as code

The supported replacement is the Railway IaC SDK: `.railway/railway.ts`, driven by
`railway config migrate` to produce the starting point. It requires Node and npm, which
this Java repository does not otherwise have, so it was not adopted. If it is adopted:

- **at most one system may manage a service.** Railway will not merge a dashboard change
  with a config change; they overwrite each other in alternating, unpredictable order.
  Pick one owner for each service and write it down.

## See also

- `docs/decisions/0001-railway-deploy-and-honest-money-path.md` — why Railway over Render,
  the cost of the root-then-drop entrypoint, and why the client-IP question is a known gap
  rather than a fix.
- `src/main/resources/application-prod.yml` — the profile `SPRING_PROFILES_ACTIVE=prod`
  activates.
- `src/main/resources/application.yml` — every environment variable the application reads.
- `Dockerfile`, `docker/entrypoint.sh` — the image and its privilege drop.
