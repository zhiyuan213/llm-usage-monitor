ALTER TABLE workspaces ADD COLUMN payment_provider text NOT NULL DEFAULT 'paddle'
 CHECK (payment_provider IN ('paddle','creem'));
ALTER TABLE checkout_intents ADD COLUMN payment_provider text NOT NULL DEFAULT 'paddle'
 CHECK (payment_provider IN ('paddle','creem'));
ALTER TABLE checkout_intents ADD COLUMN confirmed boolean NOT NULL DEFAULT false;
CREATE TABLE creem_webhook_receipts (
 event_id text PRIMARY KEY, event_type text NOT NULL, body_hash text NOT NULL,
 occurred_at timestamptz NOT NULL, outcome text NOT NULL,
 received_at timestamptz NOT NULL DEFAULT now()
);
