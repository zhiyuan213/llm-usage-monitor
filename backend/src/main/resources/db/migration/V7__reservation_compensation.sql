ALTER TABLE billable_objects ADD COLUMN charged boolean NOT NULL DEFAULT true;
ALTER TABLE billable_objects ADD COLUMN charge_generation integer NOT NULL DEFAULT 1;
ALTER TABLE billable_objects ADD COLUMN reservation_batch uuid REFERENCES ingest_batches(id);
-- Batch identity is a permanent billing reference, so a referenced batch result may outlive 8 days.
