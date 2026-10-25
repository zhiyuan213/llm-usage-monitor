ALTER TABLE outbox ADD COLUMN budget_project_id uuid REFERENCES projects(id);
CREATE TABLE project_budgets (
 project_id uuid PRIMARY KEY REFERENCES projects(id),
 monthly_budget numeric(24,6) NOT NULL CHECK(monthly_budget>0 AND monthly_budget<=1000000000),
 warning_percent integer NOT NULL DEFAULT 80 CHECK(warning_percent BETWEEN 50 AND 95),
 enabled boolean NOT NULL DEFAULT false, forecast_enabled boolean NOT NULL DEFAULT true,
 next_check timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX budget_due ON project_budgets(next_check) WHERE enabled;
CREATE TABLE budget_notifications (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id),
 period_start timestamptz NOT NULL, period_end timestamptz NOT NULL,
 kind text NOT NULL CHECK(kind IN ('warning','exceeded','forecast')),
 snapshot jsonb NOT NULL, outbox_id bigint NOT NULL UNIQUE REFERENCES outbox(id),
 created_at timestamptz NOT NULL DEFAULT now(), cancelled_at timestamptz,
 UNIQUE(project_id,period_start,kind)
);
CREATE INDEX budget_history ON budget_notifications(project_id,created_at DESC);
