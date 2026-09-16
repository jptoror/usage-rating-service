---
description: Scaffold a new domain module following this project's clean-architecture layering
---

Create a new domain module named: $ARGUMENTS

Follow the `clean-architecture` skill. Create the layer directories under
`src/main/kotlin/com/revenium/usage/<module>/`:

- `domain/` — entities, value objects, port interfaces. No framework imports.
- `application/` — use cases, transaction boundaries.
- `infrastructure/` — port implementations.
- `api/` — only if the module is exposed over HTTP.

Respect the dependency rule: `domain` imports nothing from other layers or modules.
Define ports in the module that needs them, and implement them in the module that
provides the capability.

Before writing code, state which existing modules this one will depend on and through
which ports, so the coupling is deliberate rather than accidental.
