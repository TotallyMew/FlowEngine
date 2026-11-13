# Roadmap

| Phase | Status | Scope |
| --- | --- | --- |
| 1. Foundation | Implemented | Spring Boot and React applications, Java 25 Maven Wrapper, Flyway, PostgreSQL, RabbitMQ, health, OpenAPI, Compose, CI |
| 2. Vertical slice | Implemented | Create, rename, duplicate, delete, save and load drafts. Edit node positions and connections. Run saved graphs through RabbitMQ and inspect persisted history |
| 3. Execution | Started | DAG validation, deadline handling, recovery of abandoned claims, per-node outcomes, Condition routing, Transform Data, and Webhook Trigger implemented. Add SSRF-protected HTTP Request |
| 4. Product | Started | Operator login, ownership checks, draft revisions, and run history implemented. Add database-backed accounts, publication and activation, immutable published versions, and history filters |
| 5. Production readiness | Planned | Review authentication and network controls, load-test dispatch and large graphs, add retention and backups, and prepare hosted deployment |

The next increment is an **HTTP Request** action with destination validation, response limits, and timeouts.
