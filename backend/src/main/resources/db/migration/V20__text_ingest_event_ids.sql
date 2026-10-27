-- Additive migration: old API/Worker images retain their UUID column during rolling deployment/rollback.
ALTER TABLE ingest_events ADD COLUMN event_key text;
UPDATE ingest_events SET event_key=event_id::text;
ALTER TABLE ingest_events ALTER COLUMN event_key SET NOT NULL;
CREATE UNIQUE INDEX ingest_event_key ON ingest_events(project_id,event_key);
CREATE FUNCTION fill_legacy_ingest_event_key() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.event_key IS NULL THEN NEW.event_key := NEW.event_id::text; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER legacy_ingest_event_key BEFORE INSERT ON ingest_events
FOR EACH ROW EXECUTE FUNCTION fill_legacy_ingest_event_key();
