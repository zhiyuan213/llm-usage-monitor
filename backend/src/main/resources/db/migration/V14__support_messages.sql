-- Platform roles are granted by an operator, never by account/profile APIs.
ALTER TABLE users ADD COLUMN is_admin boolean NOT NULL DEFAULT false;
CREATE TABLE support_threads (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), client_id uuid NOT NULL,
 title text NOT NULL CHECK(octet_length(title) BETWEEN 1 AND 240),
 body text NOT NULL CHECK(octet_length(body) BETWEEN 1 AND 8000),
 reply_count integer NOT NULL DEFAULT 0 CHECK(reply_count BETWEEN 0 AND 200),
 last_sender_kind text NOT NULL DEFAULT 'user' CHECK(last_sender_kind IN ('user','admin')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,client_id)
);
CREATE INDEX support_threads_owner ON support_threads(user_id,updated_at DESC,id DESC);
CREATE INDEX support_threads_inbox ON support_threads(updated_at DESC,id DESC);
CREATE TABLE support_replies (
 id uuid PRIMARY KEY, thread_id uuid NOT NULL REFERENCES support_threads(id),
 author_id uuid NOT NULL REFERENCES users(id), client_id uuid NOT NULL,
 sender_kind text NOT NULL CHECK(sender_kind IN ('user','admin')),
 body text NOT NULL CHECK(octet_length(body) BETWEEN 1 AND 8000),
 sequence integer NOT NULL CHECK(sequence>0), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(author_id,client_id), UNIQUE(thread_id,sequence)
);
