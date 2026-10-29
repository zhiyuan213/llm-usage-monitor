-- Preserve the event identifier belonging to the currently materialized revision.
ALTER TABLE objects ADD COLUMN event_id text;
UPDATE objects o SET event_id=e.event_key
FROM ingest_events e
WHERE e.project_id=o.project_id AND e.processed
  AND e.payload->>'type'=o.type AND e.payload->>'objectId'=o.object_id
  AND coalesce((e.payload->>'revision')::bigint,1)=o.revision;
