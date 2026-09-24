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
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:3000,http://localhost:5173,http://localhost:8081,https://*.ngrok-free.app,https://*.ngrok-free.dev}"
export OPENAPI_ENABLED="${OPENAPI_ENABLED:-true}"
export SWAGGER_ENABLED="${SWAGGER_ENABLED:-true}"
export TRUST_FORWARDED_HEADERS=true
export TRUSTED_PROXIES="${TRUSTED_PROXIES:-0.0.0.0/0}"
export FORWARD_HEADERS_STRATEGY="${FORWARD_HEADERS_STRATEGY:-framework}"
export OPENAPI_ENABLED="${OPENAPI_ENABLED:-true}"
export SWAGGER_ENABLED="${SWAGGER_ENABLED:-true}"
export OPENAPI_PUBLIC="${OPENAPI_PUBLIC:-true}"
export MANAGEMENT_HEALTH_MAIL_ENABLED=false
export MEDIA_ROOT="${MEDIA_ROOT:-./uploads}"

exec ./mvnw spring-boot:run