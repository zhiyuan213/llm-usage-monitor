CREATE INDEX objects_trace_score_lookup ON objects
 (project_id,(data->>'targetType'),(data->>'targetId'),started_at DESC,object_id)
 WHERE type='score';
