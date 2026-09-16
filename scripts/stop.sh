#!/usr/bin/env bash
#
# Stops everything. Pass --clean to delete the database volume as well, which is
# what you want before a run that must start from an empty database.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

if [ "${1:-}" = "--clean" ]; then
    section "Stopping and removing volumes"
    docker compose -f docker-compose.yml -f docker-compose.scale.yml down -v --remove-orphans 2>/dev/null \
        || docker compose down -v --remove-orphans
    info "Database volume deleted; the next start begins from an empty database."
else
    section "Stopping"
    docker compose -f docker-compose.yml -f docker-compose.scale.yml down --remove-orphans 2>/dev/null \
        || docker compose down --remove-orphans
    info "Data kept. Use --clean to delete it."
fi
