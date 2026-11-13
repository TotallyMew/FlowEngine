ALTER TABLE executions
    ADD COLUMN input jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(input) = 'object');
