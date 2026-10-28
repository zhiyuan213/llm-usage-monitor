CREATE TABLE redis_ingest_results (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  actual_batch uuid,
  outcome jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX redis_ingest_results_created_idx ON redis_ingest_results(created_at);
