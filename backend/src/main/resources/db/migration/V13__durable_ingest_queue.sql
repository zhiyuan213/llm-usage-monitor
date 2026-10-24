-- Queue receipts are internal. HTTP 200 is issued only after quota/identity acceptance commits.
CREATE INDEX batch_pending_project_group ON ingest_batches(project_id,created_at,id)
 WHERE status='pending';
-- Aggregation removes its temporary batch. FK checks must not scan every billed identity.
CREATE INDEX billable_reservation_batch ON billable_objects(reservation_batch)
 WHERE reservation_batch IS NOT NULL;
CREATE TABLE ingest_quota_blocks (
 id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL REFERENCES workspaces(id),
 project_id uuid NOT NULL REFERENCES projects(id),
 period_start timestamptz,
 requested_units bigint NOT NULL CHECK(requested_units>=0),
 allocated_units bigint NOT NULL DEFAULT 0 CHECK(allocated_units>=0),
 consumed_units bigint NOT NULL DEFAULT 0 CHECK(consumed_units>=0),
 state text NOT NULL DEFAULT 'reserved' CHECK(state IN ('reserved','settled')),
 created_at timestamptz NOT NULL DEFAULT now(),
 settled_at timestamptz,
 CHECK(allocated_units<=requested_units AND consumed_units<=allocated_units),
 CHECK(state<>'settled' OR (consumed_units=allocated_units AND period_start IS NOT NULL AND settled_at IS NOT NULL))
);
ALTER TABLE usage_ledger ADD COLUMN quota_block_id uuid REFERENCES ingest_quota_blocks(id);
CREATE INDEX usage_ledger_quota_block ON usage_ledger(quota_block_id) WHERE quota_block_id IS NOT NULL;

CREATE FUNCTION attach_ingest_quota_block() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE block text;
BEGIN
 block:=current_setting('metertrail.quota_block',true);
 IF block IS NOT NULL AND block<>'' THEN NEW.quota_block_id:=block::uuid; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER usage_ledger_ingest_block BEFORE INSERT ON usage_ledger
 FOR EACH ROW EXECUTE FUNCTION attach_ingest_quota_block();

-- A crash cannot leave a committed, unaccounted quota allocation behind.
CREATE FUNCTION require_settled_ingest_block() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b ingest_quota_blocks; total_units bigint;
BEGIN
 SELECT * INTO b FROM ingest_quota_blocks WHERE id=NEW.id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 IF b.state<>'settled' THEN RAISE EXCEPTION 'unsettled ingest quota block'; END IF;
 SELECT coalesce(sum(l.units),0) INTO total_units FROM usage_ledger l WHERE quota_block_id=b.id;
 IF total_units<>b.consumed_units OR EXISTS(SELECT 1 FROM usage_ledger WHERE quota_block_id=b.id
       AND (workspace_id<>b.workspace_id OR project_id<>b.project_id OR period_start<>b.period_start))
 THEN RAISE EXCEPTION 'ingest quota block ledger mismatch'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER quota_block_must_settle AFTER INSERT OR UPDATE ON ingest_quota_blocks
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_settled_ingest_block();

CREATE TABLE ingest_accept_queue (
 id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL REFERENCES workspaces(id),
 project_id uuid NOT NULL REFERENCES projects(id),
 key_hash text NOT NULL,
 payload jsonb,
 received integer NOT NULL CHECK(received BETWEEN 1 AND 500),
 payload_bytes integer NOT NULL CHECK(payload_bytes BETWEEN 1 AND 1048576),
 state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','accepted','rejected','quarantined')),
 http_status integer,
 response jsonb,
 attempts integer NOT NULL DEFAULT 0,
 available_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 quota_block_id uuid REFERENCES ingest_quota_blocks(id),
 last_error text,
 CHECK((state='queued' AND payload IS NOT NULL AND response IS NULL AND completed_at IS NULL)
    OR (state IN ('accepted','rejected') AND payload IS NULL AND response IS NOT NULL AND http_status IS NOT NULL AND completed_at IS NOT NULL)
    OR (state='quarantined' AND payload IS NOT NULL AND response IS NOT NULL AND http_status=503 AND completed_at IS NOT NULL))
);
CREATE INDEX accept_queue_pending ON ingest_accept_queue(available_at,created_at,id) WHERE state='queued';
CREATE INDEX accept_queue_project ON ingest_accept_queue(project_id,created_at,id) WHERE state='queued';
CREATE INDEX accept_queue_completed ON ingest_accept_queue(completed_at) WHERE state<>'queued';
ALTER TABLE ingest_accept_queue SET (autovacuum_vacuum_scale_factor=0.02,autovacuum_vacuum_threshold=1000);
CREATE TABLE ingest_queue_budget (
 id integer PRIMARY KEY CHECK(id=1),
 requests bigint NOT NULL DEFAULT 0 CHECK(requests>=0),
 events bigint NOT NULL DEFAULT 0 CHECK(events>=0),
 bytes bigint NOT NULL DEFAULT 0 CHECK(bytes>=0)
);
INSERT INTO ingest_queue_budget(id) VALUES(1);
