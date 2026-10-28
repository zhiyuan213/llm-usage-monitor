-- Public prices and project overrides share immutable evidence IDs.
ALTER TABLE price_versions ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE price_versions ADD COLUMN source text NOT NULL DEFAULT 'custom';
ALTER TABLE price_versions ADD COLUMN available boolean NOT NULL DEFAULT true;
ALTER TABLE price_versions ADD COLUMN time_bands jsonb NOT NULL DEFAULT '[]';
ALTER TABLE price_versions ADD COLUMN fetched_at timestamptz;
ALTER TABLE price_versions ADD COLUMN cached_known boolean NOT NULL DEFAULT true;
CREATE UNIQUE INDEX public_price_version ON price_versions(provider,model,effective_at) WHERE project_id IS NULL;
CREATE INDEX public_price_lookup ON price_versions(provider,model,effective_at DESC) WHERE project_id IS NULL;
ALTER TABLE projects ADD COLUMN public_pricing boolean NOT NULL DEFAULT true;
ALTER TABLE objects ADD COLUMN applied_rates jsonb;
ALTER TABLE cost_entries ADD COLUMN applied_rates jsonb;
CREATE TABLE model_price_sync (
 id integer PRIMARY KEY CHECK(id=1), checked_at timestamptz, succeeded_at timestamptz,
 status text NOT NULL DEFAULT 'not_synced', model_count integer NOT NULL DEFAULT 0
);
INSERT INTO model_price_sync(id) VALUES(1);
