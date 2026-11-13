# Architecture

FlowEngine is a modular monolith. Spring Boot handles authentication, workflow management, dispatch, and consumption in a single application. PostgreSQL stores drafts and execution state. RabbitMQ transports execution IDs. React Flow edits and renders graphs. Zustand holds unsaved editor state, and TanStack Query fetches and polls the API.

```mermaid
flowchart LR
  UI[React editor and run inspector] -->|Same-origin REST| API[Spring Boot API]
  API -->|Draft and execution snapshot| DB[(PostgreSQL)]
  DB -->|Eligible pending runs| Dispatcher[Scheduled dispatcher]
  Dispatcher -->|Execution ID| Queue[RabbitMQ]
  Queue --> Worker[DAG worker]
  Worker -->|Claim and results| DB
```

## Graph and execution

A graph stores node IDs, types, labels, positions and configurations, and directed edges. The server supplies node definitions with port capabilities and field metadata. One Manual Trigger starts a runnable workflow. Every other node must have one parent and be reachable from the trigger. Fan-out is supported. Cycles and merges are rejected. A draft can contain disconnected components while it is being assembled. Draft writes use a revision precondition and JPA optimistic locking to protect concurrent edits.

The Manual Trigger passes the run’s input JSON object downstream. The run API accepts it in the optional `input` field and defaults to `{}` when omitted or null. Set Data shallow-merges JSON object keys into its parent’s output. A Log node preserves the input and records a literal message in execution history. A topological plan runs one node at a time. A failed node ends the run and marks the unvisited nodes skipped. The inspector reads the graph and input captured at enqueue time along with the node results, even after the draft is edited. Node records include timestamps, inputs, outputs, messages, and safe failure details. Status and history are fetched by polling.

## Delivery and recovery

1. The run request validates the saved revision and commits a `PENDING` row with a graph snapshot and the submitted input. That row is the durable dispatch intent.
2. The dispatcher reserves up to 32 eligible rows using PostgreSQL `FOR UPDATE SKIP LOCKED` and advances their dispatch timestamps by 10 seconds. It publishes only the execution ID to a durable RabbitMQ queue. A row still pending after the interval becomes eligible again. This covers an interrupted publish or a lost delivery. Duplicate messages can occur.
3. A conditional database update gives one worker the `RUNNING` claim. A redelivered message for an already claimed or terminal run has no effect. The worker stores all node results and transitions the run to `COMPLETED` or `FAILED` before acknowledging a successful delivery.
4. The deadline is 30 seconds from claim. The engine checks it around each node and the application periodically fails overdue claims. A worker crash after claim can leave a failed execution without node details. Claimed runs are never replayed automatically. User-initiated run requests each create a separate execution.

The delivery model permits repeated dispatch and guards against repeated claims for the same execution ID. Future external action nodes will need per-action timeouts and their own idempotency strategy.

## Security and storage

The first iteration uses one environment-provisioned operator with a BCrypt password hash and a Spring Security session. Browser writes use a CSRF token. The API checks ownership on workflows and on their associated execution history. API payloads are capped at 128 KiB, with up to 50 nodes per workflow. Swagger requires a session. The loopback configuration is intended for local development. Shared or hosted deployments will need account management, TLS and secure cookies, authentication rate limiting, and a backup and retention policy.

Workflow snapshots and results are stored in PostgreSQL JSONB. They can include user-supplied data, so use sample payloads and keep credentials out of node fields. Log messages stay in run history. Deleting a workflow deletes its executions through the database foreign key. Existing Docker volumes keep their initialized database and broker credentials across restarts.

## Stack compatibility

Tests use Spring Boot’s managed JUnit Jupiter 6 because Spring Framework 7’s integration test extension requires Jupiter 6 or higher. [SpringExtension requirements](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/context/junit/jupiter/SpringExtension.html)

The backend uses Java 25, Spring Boot 4.1, and springdoc 3.2. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [springdoc compatibility matrix](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot-)

The frontend targets Node 24 LTS with Vite 8 and React Flow 12. The npm lockfile captures the installed dependency set. TypeScript 6.0 satisfies the pinned TypeScript ESLint peer range. [Vite 8 requirements](https://vite.dev/blog/announcing-vite8) · [React Flow 12 release notes](https://reactflow.dev/whats-new/2026-09-24)
