# Kilivana Backend

Agricultural e-commerce and logistics backend: Farmers, Buyers, Suppliers, Inspectors, Drivers and Admins connected through a single REST API.

- **Stack:** Java 17, Spring Boot 3.3.x, Spring Security (JWT), Spring Data JPA, Flyway, PostgreSQL, Redis, Testcontainers.
- **Architecture:** modular monolith. Feature modules under `com.kilivana.<module>` each own their `domain`, `repository`, `service`, `api`.
- **API base:** `/api/v1`. Interactive docs via Swagger/OpenAPI (opt-in, see env vars).

## Modules

| Module | Responsibility |
|---|---|
| `auth` | signup, login, refresh, logout, forgot/reset password |
| `users` | profiles per role (buyer/supplier/farmer/inspector/driver), status, verification |
| `addresses` | buyer/seller delivery and pickup addresses with optional coordinates |
| `products` | catalog, categories, images, seller-type (FARMER/SUPPLIER), stock, moderation |
| `orders` | cart, multi-seller checkout, order state machine, stock reservation, delivery fees |
| `payments` | payment lifecycle, refunds, customer-order completion |
| `paymentgateway` | provider abstraction (M-PESA stub), idempotent webhook processing |
| `logistics` | delivery jobs, driver assignment/acceptance, status machine, GPS tracking, proof of delivery |
| `inspection` | inspections with configurable checklists, site visits, audit reports, photos, results |
| `badges` | trust badges, auto-award on passed inspection |
| `notifications` | event-driven notifications (order/payment/logistics/dispute events) |
| `disputes` | raise, escalate, resolve disputes against orders |
| `messages` | public contact intake and admin inbox |
| `audit` | interceptor-based audit log recording, query endpoint |
| `admin` | admin provisioning (bootstrap key) + dashboard/reports/monitoring APIs |

## Requirements

- Java 17+ (works on 17–21)
- PostgreSQL 16
- Redis 6+
- Docker (for Testcontainers-based integration tests)

## Run locally

```bash
export DB_URL=jdbc:postgresql://localhost:5432/kilivana
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export JWT_SECRET=change-me-to-a-long-random-secret
export ADMIN_BOOTSTRAP_KEY=some-long-bootstrap-key

./mvnw spring-boot:run
```

Flyway applies `src/main/resources/db/migration` on startup (V1–V10).

### Create the first admin

```bash
curl -X POST http://localhost:8080/api/v1/admins/signup \
  -H "X-Admin-Bootstrap-Key: $ADMIN_BOOTSTRAP_KEY" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"changeit123","fullName":"Admin","phoneNumber":"+254700000000"}'
```

### Enable Swagger UI

```bash
export OPENAPI_ENABLED=true
export SWAGGER_ENABLED=true
export OPENAPI_PUBLIC=true   # optional: expose without ADMIN role
```

The docs live at `/api-docs` (JSON) and `/swagger-ui.html` (UI).

### Expose the API publicly (ngrok etc.)

Set the CORS origin(s) that will call the API, then tunnel port 8080:

```bash
export CORS_ALLOWED_ORIGINS="https://your-app.ngrok-free.dev,http://localhost:5173,http://localhost:8081"
./mvnw spring-boot:run
# in a second terminal:
ngrok http 8080
```

Point the mobile/driver apps at the tunnel via `extra.apiUrl` in `app.json` and the admin portal via `VITE_API_URL`. See `client/README.md`.

## Key environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/kilivana` | Postgres JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | DB credentials |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis cache + rate limiting |
| `JWT_SECRET` | *(empty — required in prod)* | HS256 signing secret |
| `JWT_ACCESS_TOKEN_MINUTES` | `15` | access token TTL |
| `JWT_REFRESH_TOKEN_DAYS` | `14` | refresh token TTL |
| `ADMIN_BOOTSTRAP_KEY` | *(empty — admin signup disabled)* | enables `POST /admins/signup` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | allowed web origins |
| `RATE_LIMIT_ENABLED` | `true` | Redis/in-memory rate limiting |
| `MEDIA_ROOT` | `./uploads` | local media storage root |
| `kilivana.commerce.delivery-fee.flat` | `5.00` | flat delivery fee per seller sub-order |
| `kilivana.commerce.delivery-fee.per-km` | `0.00` | optional per-km rate (distance applied when both addresses have coordinates) |
| `PASSWORD_RESET_URL` | `http://localhost:3000/reset-password` | link target in reset emails |
| `OPENAPI_ENABLED` / `SWAGGER_ENABLED` | `false` | expose API docs |

Full list: `src/main/resources/application.yml`.

## Tests

```bash
# unit + slice tests (no Docker needed)
./mvnw test -Dtest='!*IntegrationTest'

# everything, including Testcontainers integration suites (requires Docker)
DOCKER_HOST=unix:///Users/mac/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
  ./mvnw test -DargLine="-Dapi.version=1.44"
```

> On Colima, `DOCKER_HOST` must point at the Colima socket, Ryuk must be disabled (its
> bind-mount of the docker socket is not supported inside the Colima VM), and
> `-Dapi.version=1.44` overrides Testcontainers' default engine API (1.32, below the
> daemon's 1.40 minimum). Plain Docker Desktop just needs `./mvnw test`.

Last verified: **80 tests, 0 failures, 0 errors** (BUILD SUCCESS).

## Notable design points

- **Multi-seller cart:** one checkout creates a `CustomerOrder` split into per-seller `Order` sub-orders; each sub-order gets its own stock reservation, payment and logistics job.
- **Delivery fees:** each sub-order stores `subtotal`, `delivery_fee`, `total_amount`; fee is computed server-side (never trusted from clients).
- **Order state machine:** `PLACED → CONFIRMED → READY_FOR_PICKUP → IN_PROGRESS → DELIVERED → COMPLETED`, plus `CANCELLED`/`REJECTED`; transitions enforced server-side with stock release + refund on reject/cancel.
- **Logistics state machine:** `PENDING_ACCEPTANCE → ACCEPTED → AT_PICKUP → PICKED_UP → IN_TRANSIT → DELIVERED` (or `FAILED`/`CANCELLED`); drivers accept from `/logistics/jobs/available`, admins can pre-assign via `/logistics/jobs/{id}/assign`.
- **Idempotency:** payment creation is idempotent per `idempotencyKey`; webhooks are idempotent per `externalId` under a pessimistic row lock, so duplicate gateway callbacks are safe.
- **RBAC:** enforced in the service layer on every write/read path (`requireAdmin`, ownership checks, role checks), not just by hiding UI controls.

## Client applications

The backend serves three clients, scaffolded under `client/` (see `client/README.md` for wiring):

1. **Main App** (Farmer/Buyer/Supplier/Inspector) — React Native (Expo) at `client/mobile`.
2. **Logistics App** (Driver) — React Native (Expo) at `client/driver`.
3. **Admin Portal** — React (Vite) web app at `client/admin`.

API root for all three is `/api/v1`; point them at your backend (or an ngrok tunnel) via
`extra.apiUrl` (Expo apps, `app.json`) / `VITE_API_URL` (admin portal).

See `docs/implementation.md` for the internal module and state-machine reference.
