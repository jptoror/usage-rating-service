-- Creates the runtime role before the application starts.
--
-- This mirrors docker/postgres-init/01-create-app-role.sh, and exists because of an
-- ordering constraint: Hibernate opens a connection with the application's credentials
-- while building the EntityManagerFactory, which happens before Liquibase's own
-- connection runs the migrations. A role created by a migration would therefore be
-- created too late for the very startup that needs it.
--
-- NOBYPASSRLS, and not the owner of any table: in PostgreSQL both a superuser and a
-- table owner bypass row-level security unconditionally -- even with FORCE ROW LEVEL
-- SECURITY -- so a test connecting as either would pass its isolation assertions
-- without RLS doing anything at all.
CREATE ROLE usage_app LOGIN PASSWORD 'usage_app' NOBYPASSRLS;

GRANT USAGE ON SCHEMA public TO usage_app;

-- Liquibase creates the tables afterwards, as the owner; these defaults grant the
-- application role DML on whatever it creates.
-- FOR ROLE usage_owner: default privileges apply to the role that CREATES the object,
-- and Liquibase creates the tables as the owner. Without FOR ROLE these defaults would
-- attach to the role running this script and never fire.
ALTER DEFAULT PRIVILEGES FOR ROLE usage_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usage_app;
ALTER DEFAULT PRIVILEGES FOR ROLE usage_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO usage_app;
