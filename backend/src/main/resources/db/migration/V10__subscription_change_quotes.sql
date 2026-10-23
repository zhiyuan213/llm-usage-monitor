CREATE TABLE subscription_change_quotes (
  id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  subscription_id text NOT NULL,
  plan text NOT NULL,
  provider_updated_at text NOT NULL,
  preview jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'ready',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX subscription_change_inflight ON subscription_change_quotes(workspace_id)
  WHERE status IN ('processing','submitted');
ALTER TABLE workspaces ADD COLUMN scheduled_change jsonb;
