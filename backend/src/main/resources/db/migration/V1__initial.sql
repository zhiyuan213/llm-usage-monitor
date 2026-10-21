CREATE TABLE users (
 id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text,
 verified boolean NOT NULL DEFAULT false, github_id text UNIQUE,
 language text NOT NULL DEFAULT 'en', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE workspaces (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES users(id), name text NOT NULL,
 plan text NOT NULL DEFAULT 'Free' CHECK(plan IN ('Free','Core','Pro')),
 period_start timestamptz NOT NULL DEFAULT date_trunc('month',now()),
 period_end timestamptz NOT NULL DEFAULT date_trunc('month',now())+interval '1 month',
 used_units bigint NOT NULL DEFAULT 0 CHECK(used_units>=0),
 auto_overage boolean NOT NULL DEFAULT false, overage_cap numeric(18,6) NOT NULL DEFAULT 0 CHECK(overage_cap>=0),
 subscription_id text UNIQUE, payment_updated_at timestamptz
);
CREATE TABLE sessions (token_hash text PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), csrf text NOT NULL, expires_at timestamptz NOT NULL);
CREATE TABLE verification_challenges (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), action text NOT NULL, target text NOT NULL,
 code_hash text NOT NULL, attempts integer NOT NULL DEFAULT 0, consumed boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL
);
CREATE INDEX challenge_lookup ON verification_challenges(user_id,action,created_at DESC);
CREATE TABLE projects (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), name text NOT NULL,
 environment text NOT NULL DEFAULT 'production', created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE TABLE api_keys (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id), name text NOT NULL,
 prefix text NOT NULL, key_hash text NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz
);
CREATE TABLE ingest_batches (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id), agent jsonb NOT NULL,
 status text NOT NULL DEFAULT 'pending', received integer NOT NULL, accepted integer NOT NULL DEFAULT 0,
 duplicates integer NOT NULL DEFAULT 0, rejected integer NOT NULL DEFAULT 0, errors jsonb NOT NULL DEFAULT '[]',
 created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz, attempts integer NOT NULL DEFAULT 0,
 lease_until timestamptz
);
CREATE INDEX batch_pending ON ingest_batches(status,created_at);
-- Identity rows are independent of retained analytics detail; never rebill a removed detail.
CREATE TABLE billable_objects (
 project_id uuid NOT NULL REFERENCES projects(id), type text NOT NULL, object_id text NOT NULL,
 revision bigint NOT NULL, content_hash text NOT NULL, first_accepted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(project_id,type,object_id)
);
CREATE TABLE ingest_events (
 project_id uuid NOT NULL REFERENCES projects(id), event_id uuid NOT NULL, content_hash text NOT NULL,
 batch_id uuid NOT NULL REFERENCES ingest_batches(id), event_index integer NOT NULL,
 payload jsonb NOT NULL, processed boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(project_id,event_id)
);
CREATE INDEX event_batch ON ingest_events(batch_id);
CREATE TABLE usage_ledger (
 id bigserial PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), project_id uuid NOT NULL REFERENCES projects(id),
 type text NOT NULL, object_id text NOT NULL, units integer NOT NULL CHECK(units IN (-1,1)),
 reason text NOT NULL, period_start timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(project_id,type,object_id,reason)
);
CREATE TABLE price_versions (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id), provider text NOT NULL, model text NOT NULL,
 effective_at timestamptz NOT NULL, input_rate numeric(24,12) NOT NULL CHECK(input_rate>=0),
 output_rate numeric(24,12) NOT NULL CHECK(output_rate>=0), cached_rate numeric(24,12) NOT NULL CHECK(cached_rate>=0),
 currency text NOT NULL DEFAULT 'USD', UNIQUE(project_id,provider,model,effective_at)
);
CREATE TABLE objects (
 project_id uuid NOT NULL REFERENCES projects(id), type text NOT NULL, object_id text NOT NULL,
 revision bigint NOT NULL, trace_id text, span_id text, parent_span_id text, started_at timestamptz NOT NULL,
 data jsonb NOT NULL, cost numeric(24,12), price_version uuid REFERENCES price_versions(id),
 PRIMARY KEY(project_id,type,object_id)
);
CREATE INDEX objects_trace ON objects(project_id,trace_id);
CREATE INDEX objects_time ON objects(project_id,started_at DESC);
CREATE TABLE audit_logs (
 id bigserial PRIMARY KEY, user_id uuid, workspace_id uuid, action text NOT NULL, target text NOT NULL,
 success boolean NOT NULL, ip text, user_agent text, request_id text, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox (
 id bigserial PRIMARY KEY, recipient text NOT NULL, subject text NOT NULL, body text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz, attempts integer NOT NULL DEFAULT 0
);
CREATE TABLE alert_rules (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id), name text NOT NULL,
 metric text NOT NULL CHECK(metric IN ('error_count','cost_usd','latency_ms')), threshold numeric NOT NULL CHECK(threshold>0),
 enabled boolean NOT NULL DEFAULT true, last_triggered_at timestamptz
);
CREATE TABLE alert_events (id bigserial PRIMARY KEY, rule_id uuid NOT NULL REFERENCES alert_rules(id), value numeric NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE webhook_events (event_id text PRIMARY KEY, event_type text NOT NULL, occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE payments (id text PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), status text NOT NULL, amount numeric(18,2), currency text, created_at timestamptz NOT NULL DEFAULT now());
