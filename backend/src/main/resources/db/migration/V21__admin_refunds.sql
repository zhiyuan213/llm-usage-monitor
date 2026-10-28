CREATE TABLE admin_refunds (
 transaction_id text PRIMARY KEY REFERENCES payments(id),
 workspace_id uuid NOT NULL REFERENCES workspaces(id),
 actor_id uuid NOT NULL REFERENCES users(id),
 reason text NOT NULL,
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency text NOT NULL,
 paid_at timestamptz NOT NULL,
 provider_mode text NOT NULL CHECK(provider_mode IN ('test','prod')),
 subscription_id text,
 status text NOT NULL CHECK(status IN ('submitting','pending','requiresAction','unknown','succeeded','failed','canceled','rejected')),
 cancellation_status text NOT NULL DEFAULT 'not_required' CHECK(cancellation_status IN ('not_required','pending','completed','unknown')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
