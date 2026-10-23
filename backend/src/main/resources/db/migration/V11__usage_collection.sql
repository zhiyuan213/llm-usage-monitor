ALTER TABLE workspaces ADD COLUMN addon_units bigint NOT NULL DEFAULT 0 CHECK(addon_units>=0);
CREATE TABLE usage_charges (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id),
 subscription_id text NOT NULL, period_start timestamptz NOT NULL, period_end timestamptz NOT NULL,
 kind text NOT NULL CHECK(kind IN ('addon','overage')), units bigint NOT NULL DEFAULT 0,
 amount numeric(18,2) NOT NULL CHECK(amount>=0), currency text NOT NULL DEFAULT 'USD',
 status text NOT NULL DEFAULT 'ready', transaction_id text UNIQUE, checkout_url text,
 credited boolean NOT NULL DEFAULT false, revoked boolean NOT NULL DEFAULT false,
 provider_updated_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_overage_per_period ON usage_charges(workspace_id,period_start) WHERE kind='overage';
CREATE UNIQUE INDEX one_open_addon ON usage_charges(workspace_id) WHERE kind='addon' AND status IN ('ready','processing','submitted','unknown');
CREATE INDEX charge_pending ON usage_charges(status,created_at);
ALTER TABLE workspaces ADD COLUMN overage_units bigint NOT NULL DEFAULT 0 CHECK(overage_units>=0);
UPDATE workspaces SET overage_units=greatest(0,used_units-100000) WHERE plan<>'Free';
ALTER TABLE usage_ledger ADD COLUMN overage boolean NOT NULL DEFAULT false;
ALTER TABLE usage_charges ADD COLUMN checked_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE workspaces ADD COLUMN addon_consumed bigint NOT NULL DEFAULT 0 CHECK(addon_consumed>=0);
ALTER TABLE usage_charges ADD COLUMN consumed_units bigint NOT NULL DEFAULT 0 CHECK(consumed_units>=0 AND consumed_units<=units);
ALTER TABLE usage_ledger ADD COLUMN addon_charge_id uuid REFERENCES usage_charges(id);
