#!/bin/bash
# Creates the application role used at runtime.
#
# This role deliberately has NO BYPASSRLS and is NOT the table owner, because a
# table owner bypasses row-level security by default. Tenant isolation is only
# actually enforced when the connecting role is subject to RLS policies.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE ${APP_DB_USER} LOGIN PASSWORD '${APP_DB_PASSWORD}' NOBYPASSRLS;

    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${APP_DB_USER};
    GRANT USAGE ON SCHEMA public TO ${APP_DB_USER};

    -- Liquibase (running as the owner) creates tables later; these defaults
    -- make sure the app role gets DML rights on whatever it creates.
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${APP_DB_USER};
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO ${APP_DB_USER};
EOSQL
