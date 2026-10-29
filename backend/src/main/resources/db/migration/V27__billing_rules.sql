-- Existing economic evidence remains unchanged. Rules apply only on new revisions.
ALTER TABLE price_versions ADD COLUMN billing_rules jsonb NOT NULL DEFAULT '[]';
