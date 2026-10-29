-- Keep Free natural-month consumption separate from paid subscription periods.
CREATE TABLE billing_usage_periods (
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  plan text NOT NULL CHECK (plan IN ('Free','Core','Pro')),
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,
  used_units bigint NOT NULL CHECK (used_units >= 0),
  PRIMARY KEY (workspace_id, plan, period_start)
);

INSERT INTO billing_usage_periods(workspace_id,plan,period_start,period_end,used_units)
SELECT id,plan,period_start,period_end,used_units FROM workspaces;
