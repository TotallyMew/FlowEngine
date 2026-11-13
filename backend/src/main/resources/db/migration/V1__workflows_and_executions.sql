CREATE TABLE workflows (
    id uuid PRIMARY KEY,
    owner varchar(80) NOT NULL,
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0),
    graph jsonb NOT NULL CHECK (jsonb_typeof(graph) = 'object'),
    revision bigint NOT NULL CHECK (revision >= 0),
    updated_at timestamptz NOT NULL
);

CREATE INDEX workflows_owner_updated ON workflows (owner, updated_at DESC, id);

CREATE TABLE executions (
    id uuid PRIMARY KEY,
    workflow_id uuid NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    workflow_revision bigint NOT NULL CHECK (workflow_revision >= 0),
    graph jsonb NOT NULL CHECK (jsonb_typeof(graph) = 'object'),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    requested_at timestamptz NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    next_dispatch_at timestamptz NOT NULL,
    deadline_at timestamptz,
    results jsonb NOT NULL CHECK (jsonb_typeof(results) = 'array'),
    error text,
    CHECK (
        (status = 'PENDING' AND started_at IS NULL AND finished_at IS NULL AND deadline_at IS NULL AND error IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL AND deadline_at IS NOT NULL AND error IS NULL)
        OR (status = 'COMPLETED' AND started_at IS NOT NULL AND finished_at IS NOT NULL AND deadline_at IS NOT NULL AND error IS NULL)
        OR (status = 'FAILED' AND started_at IS NOT NULL AND finished_at IS NOT NULL AND deadline_at IS NOT NULL AND error IS NOT NULL)
    )
);

CREATE INDEX executions_workflow_requested ON executions (workflow_id, requested_at DESC, id);
CREATE INDEX executions_pending ON executions (next_dispatch_at) WHERE status = 'PENDING';
CREATE INDEX executions_running ON executions (deadline_at) WHERE status = 'RUNNING';
