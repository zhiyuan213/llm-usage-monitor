CREATE TABLE alert_evidence (
 rule_id uuid NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
 type text NOT NULL, object_id text NOT NULL, revision bigint NOT NULL,
 value numeric NOT NULL, window_value numeric NOT NULL, started_at timestamptz NOT NULL,
 notified_at timestamptz,
 PRIMARY KEY(rule_id,type,object_id)
);
CREATE INDEX alert_evidence_pending ON alert_evidence(rule_id) WHERE notified_at IS NULL;
ALTER TABLE alert_events ADD COLUMN evidence_count bigint,
 ADD COLUMN first_started_at timestamptz, ADD COLUMN last_started_at timestamptz;

-- Preserve the evidence covered by existing notifications when upgrading.
INSERT INTO alert_evidence(rule_id,type,object_id,revision,value,window_value,started_at,notified_at)
SELECT r.id,o.type,o.object_id,o.revision,
 CASE r.metric WHEN 'latency_ms' THEN (o.data->>'durationMs')::numeric
 WHEN 'cost_usd' THEN o.cost ELSE 1 END,r.threshold,o.started_at,r.last_triggered_at
FROM alert_rules r JOIN objects o ON o.project_id=r.project_id
WHERE r.last_triggered_at IS NOT NULL
 AND o.started_at>r.last_triggered_at-interval '1 hour' AND o.started_at<=r.last_triggered_at
 AND ((r.metric='latency_ms' AND (o.data->>'durationMs')::numeric>=r.threshold)
 OR (r.metric='cost_usd' AND o.cost>0)
 OR (r.metric='error_count' AND o.data->>'status'='error'));
