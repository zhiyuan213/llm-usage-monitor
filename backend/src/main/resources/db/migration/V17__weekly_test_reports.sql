ALTER TABLE insight_weekly_reports ADD COLUMN test_email boolean NOT NULL DEFAULT false;
ALTER TABLE insight_weekly_reports DROP CONSTRAINT insight_weekly_reports_project_id_week_end_key;
CREATE UNIQUE INDEX insight_weekly_real_week ON insight_weekly_reports(project_id,week_end) WHERE NOT test_email;
CREATE INDEX insight_weekly_test_cooldown ON insight_weekly_reports(project_id,created_at DESC) WHERE test_email;
