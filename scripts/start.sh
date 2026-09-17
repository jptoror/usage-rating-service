#!/usr/bin/env bash
#
# Starts the service and its database, and waits until it is actually ready.
#
#   ./scripts/start.sh            single instance on :8080
#   ./scripts/start.sh --scale 3  three instances behind nginx on :8080
#
# The --scale form is what demonstrates multi-instance coordination: three workers
# poll the same outbox and must take disjoint batches.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

SCALE=1
[ "${1:-}" = "--scale" ] && SCALE="${2:-3}"

if [ ! -f .env ]; then
    cp .env.example .env
    info "Created .env from .env.example"
fi

if [ "$SCALE" -gt 1 ]; then
    section "Starting $SCALE application instances"
    docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --build --scale app="$SCALE"
else
    section "Starting the service"
    docker compose up -d --build
fi

info "Waiting for the service to become healthy..."
wait_for_health

section "Ready"
info "Health      $BASE_URL/actuator/health"
info "OpenAPI     $BASE_URL/v3/api-docs"
info "Swagger UI  $BASE_URL/swagger-ui.html"
[ "$SCALE" -gt 1 ] && info "Instances   $SCALE (load balanced)"
echo
info "Run the end-to-end checks:  ./scripts/e2e-test.sh"
