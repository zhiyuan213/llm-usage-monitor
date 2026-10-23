CREATE INDEX objects_project_type_time
  ON objects(project_id, type, started_at DESC);
