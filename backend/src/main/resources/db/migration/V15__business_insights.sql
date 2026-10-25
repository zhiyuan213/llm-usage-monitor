ALTER TABLE outbox ADD COLUMN insight_project_id uuid REFERENCES projects(id);
CREATE TABLE insight_releases (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id),
 name text NOT NULL CHECK(octet_length(name) BETWEEN 1 AND 240),
 released_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(project_id,name,released_at)
);
CREATE INDEX insight_release_time ON insight_releases(project_id,released_at DESC);
CREATE TABLE insight_weekly_subscriptions (
 project_id uuid PRIMARY KEY REFERENCES projects(id), enabled boolean NOT NULL DEFAULT false,
 timezone text NOT NULL DEFAULT 'UTC', next_due timestamptz NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX insight_weekly_due ON insight_weekly_subscriptions(next_due) WHERE enabled;
CREATE TABLE insight_weekly_reports (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id),
 week_end timestamptz NOT NULL, summary jsonb NOT NULL,
 outbox_id bigint NOT NULL UNIQUE REFERENCES outbox(id), cancelled_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(project_id,week_end)
);
