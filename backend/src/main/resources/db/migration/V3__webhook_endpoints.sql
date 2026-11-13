CREATE TABLE webhook_endpoints (
    workflow_id uuid PRIMARY KEY REFERENCES workflows (id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    token_hash varchar(64) NOT NULL,
    window_started_at timestamptz NOT NULL DEFAULT now(),
    request_count integer NOT NULL DEFAULT 0 CHECK (request_count >= 0)
);
