---
description: Create a new Liquibase changeset, with the constraints and RLS this project requires
---

Create a Liquibase migration for: $ARGUMENTS

Rules for this repository:

1. **A new file** in `src/main/resources/db/changelog/migrations/`, named
   `NNN-short-description.yaml` with the next sequence number. Never edit an
   applied changeset — Liquibase checksums will reject it and the deployment fails.
2. **Constraints belong in the database.** For every invariant the change introduces,
   ask whether two concurrent instances could violate it, and add the constraint if so.
3. **Monetary columns** are `NUMERIC` with explicit precision. Never `float8`.
4. **Timestamps** are `TIMESTAMPTZ`. Never `TIMESTAMP`.
5. **Tenant-scoped tables** get `ENABLE ROW LEVEL SECURITY` and a policy in the same
   changeset that creates the table — not in a follow-up.
6. **Indexes** for the query patterns the change actually introduces.
7. Include a `rollback` block where a rollback is meaningful.

Check the existing files first to match their style and numbering:

!`ls -1 src/main/resources/db/changelog/migrations/ 2>/dev/null || echo "(no migrations yet)"`
