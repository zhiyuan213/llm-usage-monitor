CREATE TABLE cost_entries (
 project_id uuid NOT NULL REFERENCES projects(id), object_id text NOT NULL, revision bigint NOT NULL,
 started_at timestamptz NOT NULL, usage jsonb, price_version uuid REFERENCES price_versions(id),
 estimated_cost numeric(36,12), source text NOT NULL DEFAULT 'estimated',
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(project_id,object_id,revision)
);
CREATE INDEX cost_entries_time ON cost_entries(project_id,started_at);
