# 0001. Deploy on Railway, refuse simulated money, and configure the platform by hand

Date: 2026-09-30
Status: accepted
Scope: production deployment target, payment-gateway trust boundary, deployment
configuration surface, container privilege model.

## Context

KILIVANA had to answer four questions that were previously answered by accident.

**Where does this run.** The repository carried a `render.yaml` Blueprint and a
`railway.toml` at once, neither of which had ever been executed. The two disagree about
how the volume is mounted (`render.yaml` declares a disk, `railway.toml` declared a
`[[volumes]]` section that is not in Railway's schema at all). Nothing had ever proved
the application starts, binds a port, or reaches a database on either platform.

**Does the application bind the port the platform routes to.** `application.yml:53` read
`server.port: ${SERVER_PORT:8080}`. Railway injects `PORT` — at runtime only, never at
build time — and routes to whatever that says. The application therefore listened on
8080 while Railway routed to, say, 8081, and the deployment failed with a 502
"Application Failed to Respond" that named nothing the operator had changed. This is the
classic failure where the bug is invisible from inside the container.

**What does the payment webhook do, and who believes it.** `PaymentWebhookController`
accepted `POST /api/v1/webhooks/payments` with no signature, no shared secret, and no
authentication. Any party who could reach the public URL could post
`{"eventType":"VERIFIED","externalId":"…","reference":"…"}` and drive a payment to
VERIFIED. There was no gateway that would ever *send* such a callback, so the endpoint
could not do anything except receive a forgery.

**Who owns the media volume.** `Dockerfile` created and chowned `/data/uploads` at build
time. Railway mounts volumes as root, and a mount shadows the image directory, so that
chown is irrelevant the moment a volume is attached. The JVM, running as uid 1000, then
had no write access and every upload failed with a 400 that told the customer their
request was malformed.

## Invariants preserved

From `.magician/repo-map.md`:

1. **Authorization is server-side and role/ownership-scoped** — unchanged; no
   authorization decision moved.
2. **Money and status transitions are terminal and auditable** — *strengthened*. An
   unauthenticated unsigned webhook could previously drive a payment to VERIFIED. It is
   gone, and the simulated gateway is now explicitly non-authoritative: it cannot move
   money and it refuses refunds.
3. **Tokens are scoped and expiring** — unchanged.
4. **Rate limits fail closed for authentication endpoints** — unchanged for those
   endpoints (login 5, register 5, forgot-password 3, admin-bootstrap 3). Internal probe
   paths were exempted. The *general* limit was not "raised" — it was never wired, and the
   dead configuration that implied one has been deleted; see "Decision".
5. **Schema changes ship as Flyway migrations** — no schema change in this decision.
6. **Secrets come from the environment** — *strengthened*. A deployment preflight now
   refuses to start on `prod` with a missing, short, or repository-known secret, and
   refuses a memory rate-limit backend whose counters are per-instance.
7. **Media uploads are size-limited and content-addressed** — unchanged in `LocalFileStorage`.

## Candidates considered

### 1. Railway, dashboard-configured (chosen)

| | |
|---|---|
| Core idea | Build the `Dockerfile` on Railway; configure the service in the dashboard |
| Advantage | `PORT` is injected and routed correctly once bound; volumes, plugins and healthchecks are all visible in one UI; no config-file schema to rot |
| Weakness | Configuration lives in the dashboard, so it is invisible to review and to a new engineer reading the repository |
| Scaling | Volume size and replica count are plan limits; rate-limit state lives in the Redis plugin, so it is shared across replicas |
| Security | Secrets live in Railway's vault, not in git — good. `RAILWAY_RUN_UID=0` reintroduces a root window, see "Costs accepted" |
| Ops | One place to look at logs, variables, and deploy history. Nothing to debug at 3am that a dashboard cannot show |

### 2. Railway with `railway.toml` Config as Code

| | |
|---|---|
| Core idea | Keep declaring the deployment in `railway.toml` |
| Advantage | Reviewable in the repository |
| Weakness | **Removed by Railway on 2026-12-01.** After that date the file is silently not read: the service looks configured and is configured by nothing. New services cannot opt in at all. Its `[[volumes]]` section was never valid schema, so the one thing it claimed to do was already fiction |
| Scaling | n/a |
| Security | n/a |
| Ops | A silent no-op after the cutoff is the worst possible failure mode for a config file |

### 3. Render, keeping the existing Blueprint

| | |
|---|---|
| Core idea | `render.yaml` already declares Postgres, Redis, a `/data/uploads` disk, and generated secrets |
| Advantage | The only target with a *working, schema-valid* declarative config already in the repo |
| Weakness | A Blueprint is all-or-nothing; a half-configured service cannot be fixed without re-running the import. Render does not inject `PORT` at all, so the port bug would have stayed invisible until the first deploy anyway |
| Scaling | Blueprint-defined |
| Security | `generateValue: true` secrets are managed and rotated by Render |
| Ops | Blueprint diffs are invisible; a dashboard change is overwritten on the next sync |

### 4. Fly.io

| | |
|---|---|
| Core idea | Provision with `fly.toml` + `fly launch`; volumes are first-class and declare a `group` for permissions |
| Advantage | Cleanest volume/permission story of the three — ownership is declared, not chowned at startup |
| Weakness | Requires a CLI and a separate account; no shared plugin marketplace equivalent; nothing about the repo was set up for it |
| Scaling | Machines and volumes scale explicitly |
| Security | Secrets via `fly secrets`, first-class |
| Ops | Two CLIs to learn |

### 5. Self-hosted: a compose file on a small VM

| | |
|---|---|
| Core idea | `docker-compose.yml` already works locally; run the same three services on a VPS |
| Advantage | Zero vendor coupling, no per-request platform tax, `PORT` is not a concept that can be got wrong |
| Weakness | TLS termination, patching, backups, and on-call are all now the team's problem |
| Scaling | Vertical only, no managed Postgres or Redis |
| Security | The entire perimeter is the team's, including certificate renewal |
| Ops | Highest cost by an order of magnitude for a team that is not running infrastructure |

## Decision

**Railway, configured in the dashboard.** `docs/deploy/railway.md` is the ordered,
copy-pasteable checklist; `railway.toml` is deleted. Railway was chosen over Render
because Railway injects and routes `PORT`, which is the variable this deployment needs to
get right, and because the volume/ownership problem it creates has a documented,
testable remedy (`RAILWAY_RUN_UID=0`) rather than Render's `generateValue` convenience.

**`server.port` is now `${PORT:${SERVER_PORT:8080}}`.** Railway injects `PORT` at runtime
only, so no `ARG PORT` or `ENV PORT` is added to the Dockerfile — a build-time `ENV PORT`
would bake in a value Railway never provides and reproduce the original 502 in a new
form. `SERVER_PORT` keeps second place so local runs are unchanged.

**The unsigned payment webhook is deleted.** There is no gateway that would ever send the
callback, so the endpoint had exactly one reachable behaviour: accepting a forgery.
Deleting it removes an unauthenticated write path into the payment state machine.
Provenance: the invariant "money and status transitions are terminal and auditable" was
already violated by the endpoint's existence; the fix is removal, not authentication,
because there is no legitimate caller to authenticate.

**The simulated gateway is non-authoritative and refunds are refused.** It remains a
`PaymentGateway` implementation for development so the payment flow stays testable, but
it cannot be mistaken for a real integration. Refunds are a seller-scoped API that now
returns a behavioural error, not a removed endpoint, because the seller-facing contract is
worth keeping while the money movement is not.

**`railway.toml` is deleted in favour of documented dashboard configuration.** Four
reasons, any one sufficient: the `[[volumes]]` section is invalid schema; Config as Code
has a hard 2026-12-01 removal date after which the file is silently ignored; new services
cannot opt in; and the file's remaining content was two documented defaults
(`healthcheckTimeout = 300`, `restartPolicyType = "ON_FAILURE"` with
`restartPolicyMaxRetries = 10`) presented as though they were deliberate configuration.

**`RAILWAY_RUN_UID=0` plus a root-then-drop entrypoint.** `docker/entrypoint.sh` starts,
chowns `MEDIA_ROOT` to `appuser:appgroup` if and only if it is running as uid 0, then
`exec su-exec appuser "$0"` so the JVM runs as uid 1000 and is PID 1. Railway documents no
privilege-drop hook and mounts volumes as root, so the container must start as root for
the chown to be possible at all.

**Deployment preflight.** `DeploymentEnvironmentValidator` runs at context construction
when and only when `prod` is active, collects *every* violation, and names the
environment variable to set for each. Secrets are never echoed — only length and a
"looks like a placeholder" hint.

**The dead general rate-limit configuration is deleted.**
`kilivana.security.rate-limit.requests-per-minute` and its
`RATE_LIMIT_REQUESTS_PER_MINUTE` binding are removed, together with the unused
`RateLimitFilter(int, Duration)` constructor. A property that binds but that no production
code reads is a control the repository claims and does not have: an operator who sets it is
not protected, and the only test that ever covered it asserted that the property bound —
which passes identically whether or not anything reads it.

*Verified history.* `git log -S 'requestsPerMinute'` shows the property was introduced in
the **initial commit `59c19b9`** and never referenced again. `RateLimitFilter` has only
ever existed in that same commit. The legacy `"all"` rule has never had a production
caller. The general rate limit was therefore never wired and never removed: it was dead
from day one, and this change is a deletion of dead code, not a reversal of a deliberate
removal.

*Wiring it instead was considered and rejected.* Four reasons, any one sufficient:

1. **The filter runs before `JwtAuthenticationFilter`**, so there is no user identity at
   decision time and buckets can only be keyed by client IP. The primary client is a React
   Native app, where a carrier-grade NAT cohort shares one egress address — an entire
   cohort would share one bucket.
2. **`RedisRateLimiter` answers `UNAVAILABLE` on any Redis error and the filter answers
   503.** A general rule turns a Redis blip into a site-wide outage, while Railway's probe
   path `/actuator/health/liveness` stays green, so nothing restarts or self-heals.
3. **The Redis keyspace would be sized by attacker-chosen source addresses**, with no
   `maxLocalKeys` equivalent and no eviction metric. Under `allkeys-lru` eviction silently
   resets counters, which is a bypass rather than a slowdown.
4. **120/min is 2 rps per egress IP.** It would throttle a shared office or NAT cohort —
   the exact failure the 120 default was meant to fix, reintroduced by applying it.

The unthrottled path is a real exposure and is recorded as a known gap below rather than
papered over. `RateLimitSecurityTest.noPathWithoutASpecialisedRuleConsumesABucket` is the
standing falsifier: it fails the day a general rule comes back.

*Deployment consequence.* A service that already has `RATE_LIMIT_REQUESTS_PER_MINUTE` set
will refuse to start after this change, and will keep refusing until the variable is
removed from the Railway service. That is intended — continuing to boot while the
operator believes in a control that does nothing is the failure this repair exists to
prevent — but it means the variable must be deleted from the dashboard in the same deploy,
which is why `docs/deploy/railway.md` no longer lists it.

**The `prod` profile trusts no proxy, and the local proxy trusts no header.**
`ClientIpResolver` reads `X-Forwarded-For`, so anything that honours those headers from an
untrusted peer lets that peer choose its own rate-limit bucket. The previous default was
`forward-headers-strategy: framework`, which honours `X-Forwarded-For` from *any* peer: one
request with a forged header, and the credential-abuse limits that are the entire
enforcement surface become bypassable with a header. Two changes:

### R1. `server.forward-headers-strategy: none` on `prod`

Recorded as its own decision because the mechanism is not obvious and the setting is
counter-intuitive to anyone who has deployed behind a proxy before.

**The mechanism.** Boot's `FORWARD_HEADERS_STRATEGY=framework` installs
`ForwardedHeaderFilter` at `Ordered.HIGHEST_PRECEDENCE`, ahead of the Spring Security filter
chain. It rewrites `request.getRemoteAddr()` from the *leftmost* client-supplied
`X-Forwarded-For` entry and then removes the header. `ClientIpResolver` therefore never sees
`X-Forwarded-For` at all, and — this is the part that matters — never gets to check the real
socket peer against the trusted-proxy CIDR list. Every rate-limit bucket becomes
caller-chosen. Turning the strategy off leaves `getRemoteAddr()` as the socket peer, which is
what `ClientIpResolver`'s right-to-left walk over the CIDR list needs in order to be safe.

Turning the framework filter *off* is therefore the strictly more secure configuration, and it
looks like the less capable one. That inversion is the whole decision.

**Provenance, and the reason it is recorded as a decision rather than a bug fix.** This was
found by the **REVIEWER** specialist, not by any test. No test failed, and none could have:
`forward-headers-strategy` was `framework`, which *honours* `X-Forwarded-For`, and every test
in the suite that cared about bucket selection set a socket peer outside the trusted list and
asserted the header was ignored — a configuration that is only reachable when the framework
filter is absent or inert. The tests were green against a deployment that let a client pick its
own login bucket. It was caught by reading the filter ordering, not by running anything.

Two changes ship with it:

- `application-prod.yml` sets `server.forward-headers-strategy: none`. With no trusted proxy
  in front of the container, the peer address *is* the client address. Where the peer *is* a
  platform proxy that the operator has listed in `TRUSTED_PROXIES`, all traffic shares the
  platform's egress address — which is why that list, not this strategy, is the thing that has
  to be right. See the known gap below.
- `scripts/run-dev.sh` sets `FORWARD_HEADERS_STRATEGY=none` **and** `TRUSTED_PROXIES=0.0.0.0/0`.
  The second line is deliberate and looks wrong: in the dev compose stack the API sits behind
  a proxy the developer controls, and if no proxy is trusted the developer cannot be rate
  limited at all locally, which would make every local test of the limiter useless. The
  loopback-range trust exists only in the dev profile and only for that reason. It is a
  footgun written down so the next reader widens it to `prod` deliberately rather than by
  copy-paste.

`ForwardedHeaderBucketSpoofingIntegrationTest` is the standing falsifier. It boots the `prod`
profile and drives real requests through the whole filter chain from a socket peer outside the
trusted list, so it goes red the day the strategy is flipped back to `framework`.

The durable proxy-tier fix — terminate TLS at a proxy that overwrites `X-Forwarded-For`, then
trust exactly that proxy's address — is not taken here because it needs infrastructure this
deployment does not have. Recorded below.

**Payment transitions are protected by optimistic locking, and nothing else.**
`BaseEntity` declares `@Version` and `V1__initial_schema.sql` gave every table a `version`
column, so JPA version checking was already in force; deleting
`PaymentRepository.findByExternalIdForUpdate` removed the only *other* mechanism (a
pessimistic `FOR UPDATE` finder) rather than replacing it. The invariant is now stated and
falsified rather than assumed: with the gateway non-authoritative, the only transitions that
can race are seller-initiated `verify` and `fail` on one `PENDING` payment, and
`PaymentConcurrencyIntegrationTest` fires eight genuinely concurrent ones and requires the
row's `version` to increment exactly once — **the number of commits, read from the database,
not inferred from HTTP status codes**. See "Verification" below for why that distinction is
the whole test.
*The brief assumed no optimistic locking existed and
asked for a `version` column to be added.* It already existed since the initial migration;
adding a second one would have been a second way to do an existing thing. Nothing is
therefore migrated, and the deviation is that the protection is proven rather than created.

**A refund is refused at the service layer, not by the gateway implementation.**
`PaymentService.refund` rejects a non-authoritative gateway before it reaches
`SimulatedPaymentGateway`, and `PaymentServiceTest` pins that with a mock gateway that
*would* happily refund. The earlier test stubbed the gateway's own refusal, so it passed
identically whether or not `PaymentService` guarded anything — the same class of tautology
as the rate-limit property, and the reason the test was rewritten to assert the decision at
the layer that makes it.

**A malformed `DATABASE_URL` is reported as a malformed URL.**
`JdbcUrls` previously returned an unparseable value untouched with null credentials, so the
driver aborted with "URL must start with 'jdbc'" while
`spring.datasource.username` kept its `${DB_USERNAME:postgres}` default — an operator
reading a wrong-credential error caused by a bad host. An unrepairable `postgres://` value
now throws `IllegalArgumentException` naming the expected form, and the message never
contains the credentials. An unrecognised scheme is still passed through: refusing every
value that fails to parse would break a platform that injects something this class has never
heard of, and the driver reports an unknown scheme better than this class could.

## Costs accepted

- **The container briefly runs as root.** This is the real price of `RAILWAY_RUN_UID=0`.
  Between PID 1 starting and `su-exec` completing, the process has root and is executing
  an attacker-reachable script if anything can write `/usr/local/bin/docker-entrypoint.sh`
  or replace `java` on `PATH`. The window is small and the blast radius is one container,
  but it is a genuine widening of the attack surface versus `USER appuser`, which simply
  could not fix a root-owned volume. It is accepted because the alternative is that
  uploads never work on Railway at all, and because a container that serves one tenant's
  data on its own volume has no lateral reach.
- **Configuration is no longer reviewable in git.** A new engineer reading the repository
  cannot see which variables a working Railway service has. Mitigated by
  `docs/deploy/railway.md` listing every variable, but not eliminated.
- **There is no general rate limit.** Every endpoint outside the four credential-abuse
  rules is unthrottled, including public writes. This is a chosen cost, not an oversight —
  the reasons are in "Decision" — and `POST /api/v1/contact-messages` is the sharpest
  instance, tracked below.
- **`/actuator/**` is never rate limited.** A client that can reach an actuator path
  directly is unthrottled there. This is deliberate: the deploy-time probe arrives from
  one shared proxy address, and a shared bucket would let the platform's own traffic spend
  a customer's allowance.
- **Media storage failures answer 503; rejected media answers 400.** The two fault domains
  are separate types (`MediaStorageException` and `InvalidMediaException`), so an unwritable
  volume tells a load balancer to back off while an unsupported upload tells the client,
  correctly, to fix its request.

## Falsification

ATTACK:  `RAILWAY_RUN_UID=0` is a privilege the platform grants the whole container, and
the entrypoint is the first thing that runs.
TRIGGER: any code path that executes before `su-exec`, or any write access to
`/usr/local/bin/docker-entrypoint.sh` or the `java` binary on `PATH`.
VERDICT: weakened
MITIGATION: the chown is the only thing that happens as root and it touches exactly one
directory; `exec su-exec appuser "$0"` re-enters the same script so the privileged surface
is one shell, not a chain. Accepted rather than fixed, because Railway documents no
alternative. Re-opens if Railway documents a uid-mapping or group-writable-volume option.

ATTACK: the deployment preflight is bypassed by setting `SPRING_PROFILES_ACTIVE` to
something other than `prod`.
TRIGGER: `SPRING_PROFILES_ACTIVE=production`, or omitting it entirely.
VERDICT: survives
MITIGATION: `ENV SPRING_PROFILES_ACTIVE=prod` is baked into the image, so the platform
value is not the only source. The reverse — a non-`prod` profile in front of a real
deployment — is caught by `docs/deploy/railway.md` requiring the variable explicitly.

ATTACK: an attacker scripts credential stuffing against `POST /api/v1/auth/login`, and the
limit that was supposed to stop them is the one that was removed.
TRIGGER: any sustained login flood from one address.
VERDICT: survives
MITIGATION: **there is no general rate limit, and none ever existed** — the claim in an
earlier draft of this record that "the general limit applies only to endpoints with no
specialised rule" was false, and there is no catch-all rule to apply anywhere. The four
credential-abuse limits are the entire enforcement surface, and login remains 5/min keyed
separately from the other rules. The falsification for "raising the limit to 120/min made
stuffing viable" is withdrawn: no such limit was ever running. What remains true is the
`POST /api/v1/contact-messages` hole below, which no limit of any kind covers.

ATTACK: the media 503 makes clients retry-loop on a permanently invalid upload.
TRIGGER: `LocalFileStorage.invalid(...)` — empty file, oversize, unsupported content type,
traversal storage key.
VERDICT: fixed
MITIGATION: the two fault domains are separate, sibling types. `invalid(...)` throws
`InvalidMediaException`, mapped to 400 with the same `INVALID_MEDIA` code; every
`IOException`-wrapping site still throws `MediaStorageException`, mapped to 503. Because
they are siblings rather than one type, neither family can be caught and re-labelled as
the other. `LocalFileStorageTest` asserts every `invalid(...)` call site and
`MediaFaultResolutionTest` drives both faults through a real controller in a sliced context
and pins 400 and 503 at the response boundary.

ATTACK: a client forges `X-Forwarded-For` and spends one credential-abuse budget per header
value.
TRIGGER: any request carrying a header the API trusts from an untrusted peer.
VERDICT: fixed on the container, unfixable as deployed
MITIGATION: `forward-headers-strategy: none` in `prod` and in `scripts/run-dev.sh`, so no
peer can nominate a bucket. This converts the vulnerability into an availability problem:
every request from behind Railway's proxy now keys on the proxy's address, so one abusive
actor can exhaust the login budget for *all* users on that proxy. The four limits are the
entire enforcement surface, so that is worse for availability than it was for integrity.
It is accepted because the alternative was unlimited credential stuffing, and because the
correct fix — a trusted reverse proxy that overwrites the header, with only its address in
the trusted range — needs infrastructure this deployment does not have. Recorded as a known
gap with an owner.

ATTACK: the entrypoint's recursive `chown` is pointed at `/` and walks the whole host
filesystem, or at a relative path that resolves against whatever the working directory
happens to be.
TRIGGER: `MEDIA_ROOT=/`, `MEDIA_ROOT=//`, or `MEDIA_ROOT=uploads` in the container
environment.
VERDICT: fixed
MITIGATION: `docker/entrypoint.sh` rejects `/`, `//`, and any non-absolute `MEDIA_ROOT`
before the `chown` runs, and `EntrypointScriptTest` executes the real script with stubbed
`chown`/`su-exec`/`java` to prove the guard runs *before* the chown and that the JVM is never
started. Root is still a precondition; this only stops the operator from aiming the
privileged step at the wrong directory.

ATTACK: two concurrent seller actions on one `PENDING` payment both commit, and the last
writer decides whether the buyer paid.
TRIGGER: `POST /api/v1/payments/verify` and `POST /api/v1/payments/{id}/fail` in flight at
the same time for one payment.
VERDICT: survives
MITIGATION: inherited JPA `@Version`. `PaymentConcurrencyIntegrationTest` releases eight
threads off one latch so they collide for real and requires a single version increment, with
the persisted status one of `VERIFIED`/`FAILED` and no response outside {200, 409}. The
version assertion is the control: it fails the day the version check is removed, the entity is
loaded outside its transaction, or the status is written without an update statement.

ATTACK: the concurrency test's own assertion is the wrong invariant, so it is green against a
money path that lets both transitions take effect.
TRIGGER: any change that makes an idempotent replay answer something other than 200, or that
makes a losing racer answer something other than 409.
VERDICT: fixed
MITIGATION: see "Verification". The earlier mixed-race assertion was "exactly one racer
answered 200", which conflated *commits* with *2xx responses*. `PaymentService.verify`
short-circuits to 200 when the payment is already `VERIFIED`
(`PaymentService.java:106`), and `markFailed` has no equivalent, so the same correct
production behaviour produced one 200 when a fail won the race and four when a verify did. The
test was therefore a coin flip on scheduling, not a control. It now reads the commit count off
the `version` column, which is the quantity the attack actually threatens, and asserts the
200/409 split for each of the two orderings exactly as it occurs.

## Known gaps

- **(TOP PRODUCTION RISK) Whether Railway's edge proxy is inside `TRUSTED_PROXIES` is
  unverified, and the answer decides whether login works at all.** R1 set
  `forward-headers-strategy: none` so no caller can nominate its own bucket. The direct
  consequence is that `request.getRemoteAddr()` is now the *socket peer*, and
  `ClientIpResolver` reads `X-Forwarded-For` **only** when that peer is inside
  `trusted-proxies`. The `prod` default list is loopback + RFC1918 + RFC4193 + link-local.
  **Nobody has deployed this to a real Railway project, so it is unknown whether Railway's
  edge proxy connects from one of those ranges.** Two outcomes, and they are opposite:
  - **Trusted (the intended case):** every client resolves to its own address and the four
    credential limits (login 5/min, signup 5/min, forgot 3/min, admin 3/min) are per client.
  - **Not trusted:** the header is discarded for every request, every client resolves to the
    proxy's address, and those four limits become **global budgets for all users combined**.
    The symptom is a total, immediate, self-inflicted login outage — legitimate users see 429
    — which reads as a rate-limit bug and is not one. One abusive actor could also spend
    everyone's credential budget, which is a real DoS on the login endpoint.

  **This is closed by a diagnostic, not by a fix, and that is a deliberate choice.** The
  correct `TRUSTED_PROXIES` value is platform-specific and cannot be derived from this
  repository: guessing it would mean either leaving a gap or reintroducing the R1 bypass by
  widening to `0.0.0.0/0`, and both are worse than not knowing. So instead
  `ClientIpResolver` emits one WARN — at most once per distinct untrusted peer per process —
  the first time it discards an `X-Forwarded-For` that arrived from a peer outside the list,
  naming the peer, stating that every client is sharing one bucket per rule, and naming
  `TRUSTED_PROXIES` as the fix. `docs/deploy/railway.md` step 7 turns that into a four-step
  first-deploy check. The client's forwarded address is never logged: it is attacker-controlled
  and unbounded. Widening the list to make the warning go away, rather than to make the
  configuration correct, is exactly the R1 bypass.

  A related misconfiguration *is* already impossible: `TRUST_FORWARDED_HEADERS=true` with an
  empty `TRUSTED_PROXIES` refuses to start, because `ProxyProperties.validate()` runs in
  `ClientIpResolver`'s constructor and the context cannot come up. `ProxyPropertiesTest` pins
  it, including the blank and separator-only spellings an operator produces by clearing the
  variable in the dashboard. No second check was added to `DeploymentEnvironmentValidator`:
  the requirement is that this state is undeployable, and it already is.

  **Owner:** the SECURITY specialist, with the operator who performs the first Railway deploy.
  **Revisit trigger:** the first successful deploy to a real Railway project. Before that
  deploy, someone must perform the step 7 check and record the answer here. The moment the
  answer is known — and it can only be known there — replace this gap with the observed peer
  range, and then run `ForwardedHeaderBucketSpoofingIntegrationTest`, which must stay green
  with the forged header still rejected and must now show the real proxy's address winning the
  bucket.
- **`POST /api/v1/contact-messages` is unthrottled.** It is `permitAll`, has no
  specialised rate-limit rule, accepts an anonymous request, writes a row, and fans out a
  domain event. Nothing bounds its request rate. Deliberately not fixed here: the obvious
  fix — a general rule — was rejected in "Decision", and a per-endpoint rule is a change
  to the security surface that deserves its own decision record. **Owner:** the SECURITY
  specialist in the next council cycle. **Follow-up:** choose between a dedicated rule for
  this path and moving `RateLimitFilter` after `JwtAuthenticationFilter` so a general rule
  can key on identity rather than egress IP; whichever is chosen must also settle the
  `UNAVAILABLE` → 503 behaviour, which would otherwise convert a Redis blip into an
  outage. Tracked in "Revisit when".
- **The dev stack trusts every proxy.** `scripts/run-dev.sh` sets
  `TRUSTED_PROXIES=0.0.0.0/0` so a locally forwarded request is still rate limited. That
  value is meaningless for `prod` and is a footgun if ever copied. **Owner:** the SECURITY
  specialist. **Follow-up:** delete it the moment a real trusted-proxy CIDR is configured,
  or when the dev stack stops needing per-client buckets.

## Revisit when

- Railway publishes a documented uid-mapping or group-permission option for volumes →
  drop `RAILWAY_RUN_UID=0` and return to `USER appuser`.
- Railway's IaC SDK becomes a first-class, non-Node-required option, or a team adopts
  Node for another reason → re-evaluate `.railway/railway.ts`, remembering that at most
  one system may manage a service.
- A real payment gateway (M-Pesa STK, Flutterwave, Paystack) is selected → the
  non-authoritative simulated gateway and the refund refusal must be replaced by that
  gateway's real signature verification, not merely re-enabled.
- `/actuator/**` ever becomes reachable without authentication → revisit the
  no-rate-limit exemption.
- `POST /api/v1/contact-messages` → the known gap above. Revisit with a decision record
  whenever the filter is moved after authentication or a per-endpoint rule is proposed.
- The first real deployment is attempted and fails → everything below marked UNVERIFIED
  is a live question.
- A reverse proxy is put in front of the container → revisit the `forward-headers-strategy`
  decision with the proxy's address, and re-run
  `ForwardedHeaderBucketSpoofingIntegrationTest` before enabling forwarding. Do not simply
  flip the strategy to `native` and keep `TRUSTED_PROXIES=0.0.0.0/0`.
- **The first real deployment succeeds** → perform the client-IP check in
  `docs/deploy/railway.md` step 7, record the answer in the top known gap above, and retire
  that gap. Everything marked UNVERIFIED below is waiting on the same deploy.

## Verification

### The concurrency test measures commits, not status codes

`PaymentConcurrencyIntegrationTest.concurrentVerifyAndFailCannotBothTakeEffect` releases four
`verify` racers and four `fail` racers off one latch at a single `PENDING` payment. The
assertion that carries the invariant is:

```java
assertThat(versionOf(payment.paymentId())).isEqualTo(versionBefore + 1);
```

`versionOf` reads the `payments.version` column, so this counts **commits** — one version
increment means exactly one racer's update reached the database.

**Why an HTTP-status assertion was wrong.** The previous assertion was
`outcome.winners.hasSize(1)`, where `Outcome.record` classified any 200 as a "winner". That
made the test red whenever a `verify` won the race, and a `verify` winning is correct
production behaviour: `PaymentService.verify` returns early with a 200 when the payment is
already `VERIFIED` (`PaymentService.java:106-108`), so the three racers that arrive after the
commit each replay the winning state without writing anything. Both orderings are correct and
both were reachable:

| Winner | Responses | Commits |
|---|---|---|
| a `fail` | 1× 200, 7× 409 | 1 |
| a `verify` | 1–4× 200 (1 commit + replays that read the row after it; the rest lose the version predicate), 4–7× 409 | 1 |

An idempotent replay legitimately answers 200, so "exactly one 200" was asserting a transport
detail the design is entitled to vary. The test was a coin flip on scheduling: it passed on one
run and failed on the next with `Expected size: 1 but was: 4 in: [200, 200, 200, 200]`. No
production code was changed to accommodate it.

The replacement asserts each ordering exactly as it occurs, which is stronger than the old
"not one" check in the `FAILED` branch and order-independent in the `VERIFIED` branch, plus
the unchanged `otherStatuses` is-empty check that catches an
`ObjectOptimisticLockingFailureException` escaping as a 500. The `Outcome` field was renamed
`winners` → `succeeded` so the misnomer that produced the bad assertion cannot recur.

**The test has teeth.** Removing `@Version` from `BaseEntity` in a scratch copy outside the
repository — production code otherwise untouched — fails both tests on the version assertion
and nothing else:

```
[ERROR]   PaymentConcurrencyIntegrationTest.concurrentVerifiesLeaveExactlyOneWinner:150
expected: 1L
 but was: 0L
[ERROR]   PaymentConcurrencyIntegrationTest.concurrentVerifyAndFailCannotBothTakeEffect:209
expected: 1L
 but was: 0L
```

The mixed race reaches line 209, i.e. it clears the 200/409 assertions first. That is the
direct demonstration that those assertions are order-independent and the version line is the
discriminator.

### The client-IP diagnostic fires for the wrong peer and stays silent for the right one

`ClientIpResolverDiagnosticTest` captures the resolver's own logger through a logback
`ListAppender` and asserts the collapse is reported with the peer address present and the
client's forwarded address absent, that a trusted peer is silent, that 25 requests from one
bad peer produce one log line, and that no header and `TRUST_FORWARDED_HEADERS=false` are both
silent. `ProxyPropertiesTest` pins that `TRUST_FORWARDED_HEADERS=true` with an empty
`TRUSTED_PROXIES` cannot start, so the one bad proxy configuration is a failed deploy rather
than a silent collapse.

## Verification status

| Claim | Status |
|---|---|
| `server.port` binds `PORT` in preference to `SERVER_PORT` | **VERIFIED** — `DeploymentConfigurationTest` resolves the property with and without each variable set |
| IPv6 host in a `postgres://` URI yields `jdbc:postgresql://[::1]:5432/db`, not `[[::1]]` | **VERIFIED** — parameterised test over `[::1]`, `[2001:db8::1]`, bracketed IPv4, plus unchanged IPv4 cases |
| Unsigned webhook cannot move a payment to VERIFIED | **VERIFIED** — endpoint deleted; no path in `docs/spec/spec.json` and no controller in `src/main/java` |
| Simulated gateway refuses refunds | **VERIFIED** — `PaymentServiceTest` |
| Rejected media answers 400; a storage fault answers 503 | **VERIFIED** — `MediaFaultResolutionTest` (real controller, sliced context), `LocalFileStorageTest` |
| Internal actuator paths are never rate limited | **VERIFIED** — `RateLimitSecurityTest` |
| No path without a specialised rule consumes a rate-limit bucket | **VERIFIED** — `RateLimitSecurityTest.noPathWithoutASpecialisedRuleConsumesABucket`; fails the day a general rule is re-wired |
| The four credential-abuse limits still run (login 5, register 5, forgot 3, admin 3) | **VERIFIED** — `RateLimitSecurityTest.everyCredentialRuleStillLimits` pins rule name, limit and window for each |
| `prod` startup fails with every violation listed at once | **VERIFIED** — `DeploymentEnvironmentValidatorTest` |
| Every JWT secret and bootstrap key this repository has ever committed is refused on `prod` | **VERIFIED** — `DeploymentEnvironmentValidatorTest` parameterised over each value recovered from `git log -p --all`, including `change-me-to-a-long-random-secret`, which is 33 characters and clears the length floor on its own |
| `prod` honours no client-nominated `X-Forwarded-For` | **VERIFIED** — `ForwardedHeaderBucketSpoofingIntegrationTest` drives the real filter chain at the `prod` setting and asserts a forged header does not change the bucket; `DeploymentConfigurationTest` pins the property |
| An operator is told, within one deploy, whether the platform is collapsing every client into one rate-limit bucket | **VERIFIED (mechanism)** — `ClientIpResolverDiagnosticTest` fires for an untrusted peer carrying `X-Forwarded-For`, stays silent for a trusted peer, names the peer, omits the client's forwarded address, and logs once per peer. **Whether Railway's edge proxy triggers it is UNVERIFIED** — see the top known gap; this has never been observed on a real Railway project |
| `TRUST_FORWARDED_HEADERS=true` with an empty `TRUSTED_PROXIES` is undeployable | **VERIFIED** — `ProxyPropertiesTest`, including the blank and separator-only spellings; `ProxyProperties.validate()` runs in `ClientIpResolver`'s constructor so the context never comes up |
| A malformed `postgres://` `DATABASE_URL` is reported as malformed, not as bad credentials | **VERIFIED** — `DeploymentConfigurationTest` asserts the message names the expected form and does not contain the password |
| The entrypoint refuses to `chown` `/`, `//`, or a relative `MEDIA_ROOT` | **VERIFIED** — `EntrypointScriptTest` executes the real script with stubbed `chown`/`su-exec`/`java` |
| Concurrent verify/fail on one payment commits exactly once | **VERIFIED** — `PaymentConcurrencyIntegrationTest`; **one version increment read from `payments.version`**, final status one of `VERIFIED`/`FAILED`, no response outside {200, 409}. Not counted via HTTP status, which is not a commit count — see "Verification" above |
| Removing `@Version` turns the concurrency test red | **VERIFIED by falsification** — `@Version` deleted from `BaseEntity` in a scratch copy outside the repository; both tests fail on the version assertion and no earlier assertion |
| `PaymentService` refuses a refund against a non-authoritative gateway that would otherwise allow it | **VERIFIED** — `PaymentServiceTest.theServiceRefusesANonAuthoritativeGatewayThatWouldHappilyRefund`, mock gateway stubbed to succeed |
| The `simulated` gateway value on `prod` refuses startup | **VERIFIED** — `DeploymentEnvironmentValidatorTest` |
| `RATE_LIMIT_REQUESTS_PER_MINUTE` present on `prod` refuses startup; absent starts | **VERIFIED** — `DeploymentEnvironmentValidatorTest` |
| General rate-limit default is 120/min | **PROCESS FAILURE — never was a control.** An earlier draft of this record marked it **VERIFIED**. The test behind it (`DeploymentConfigurationTest.rateLimitDefaults`) asserted only that a property *binds*, which passes identically whether or not any production code reads the value. A council raised a default and declared it verified for a rule that did not exist; the property had been dead since the initial commit `59c19b9`. The assertion is removed, the property deleted, and the invariant is now pinned by a falsifier that exercises the request path. The lesson: "the property binds" is not evidence that a control runs. See "Decision" and the standing falsifier. |
| Container drops from root to uid 1000 | **VERIFIED** — only by text assertion on `Dockerfile` and `docker/entrypoint.sh`; see known gaps |
| Railway volumes are mounted as root and shadow the image directory | **UNVERIFIED** — from documentation only, never observed on a live Railway project |
| `RAILWAY_RUN_UID=0` makes the entrypoint's chown succeed in practice | **UNVERIFIED** — no Railway deployment has been attempted |
| `PORT` is injected at runtime and never at build time | **UNVERIFIED on Railway**, verified against documentation only |
| Railway's healthcheck is a deploy-time gate and is not polled afterwards | **UNVERIFIED** — from documentation only |
