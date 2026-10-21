ALTER TABLE workspaces ADD COLUMN customer_id text;
ALTER TABLE payments ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE payment_adjustments(id text PRIMARY KEY,workspace_id uuid NOT NULL REFERENCES workspaces(id),transaction_id text NOT NULL,action text NOT NULL,status text NOT NULL,amount numeric(18,2),currency text,updated_at timestamptz NOT NULL);
