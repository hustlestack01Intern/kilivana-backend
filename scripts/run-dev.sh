#!/usr/bin/env bash
# Kilivana backend - local run with Dockerized Postgres + Redis.
set -euo pipefail
cd "$(dirname "$0")/.."

export DB_URL="${DB_URL:-jdbc:postgresql://127.0.0.1:5432/kilivana}"
export DB_USERNAME="${DB_USERNAME:-postgres}"
export DB_PASSWORD="${DB_PASSWORD:-postgres}"
export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export JWT_SECRET="${JWT_SECRET:-dev-only-secret-change-me-4f3f2d1c}"
export JWT_ACCESS_TOKEN_MINUTES="${JWT_ACCESS_TOKEN_MINUTES:-30}"
export ADMIN_BOOTSTRAP_KEY="${ADMIN_BOOTSTRAP_KEY:-kilivana-dev-bootstrap-key}"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:3000,http://localhost:5173,http://localhost:8081,https://*.ngrok-free.app,https://*.ngrok-free.dev,https://*.github.io}"
export OPENAPI_ENABLED="${OPENAPI_ENABLED:-true}"
export SWAGGER_ENABLED="${SWAGGER_ENABLED:-true}"
# Forwarded headers are OFF locally, exactly as docker-compose.yml sets them. Boot's
# ForwardedHeaderFilter rewrites getRemoteAddr() from the leftmost, client-supplied
# X-Forwarded-For entry before any check runs, which would let a client choose its own
# rate-limit bucket; ClientIpResolver exists to avoid that, but with no proxy in front of a
# local run there is nothing to verify a chain against. TRUSTED_PROXIES=0.0.0.0/0 is also
# refused at startup by design — a proxy list that trusts the whole internet is a
# misconfiguration, not a convenience.
export TRUST_FORWARDED_HEADERS=false
export TRUSTED_PROXIES="${TRUSTED_PROXIES:-127.0.0.1/32,::1/128}"
# Must match application-prod.yml: none. See the comment there.
export FORWARD_HEADERS_STRATEGY="${FORWARD_HEADERS_STRATEGY:-none}"
export OPENAPI_ENABLED="${OPENAPI_ENABLED:-true}"
export SWAGGER_ENABLED="${SWAGGER_ENABLED:-true}"
export OPENAPI_PUBLIC="${OPENAPI_PUBLIC:-true}"
export MANAGEMENT_HEALTH_MAIL_ENABLED=false
# Local password reset only works in simulation: with this false the mail sender throws
# (no MAIL_HOST locally), PasswordResetService swallows the failure and invalidates the
# token, so /forgot-password silently succeeds and /reset-password always fails. Safe
# locally because simulate-delivery is refused outright on the prod profile, at both
# PasswordResetProperties binding and the deployment preflight.
export KILIVANA_SECURITY_PASSWORD_RESET_SIMULATE_DELIVERY="${KILIVANA_SECURITY_PASSWORD_RESET_SIMULATE_DELIVERY:-true}"
export MEDIA_ROOT="${MEDIA_ROOT:-./uploads}"

exec ./mvnw spring-boot:run