CREATE TABLE checkout_intents (id uuid PRIMARY KEY,workspace_id uuid NOT NULL REFERENCES workspaces(id),plan text NOT NULL,transaction_id text UNIQUE,created_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE workspaces ADD COLUMN subscription_status text NOT NULL DEFAULT 'active';
