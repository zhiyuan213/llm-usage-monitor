# Account feedback and platform administrators

Users open `/app/messages` under Account to start feedback conversations and add
follow-ups. Administrators open `/app/admin-messages` under Administrator to read
and reply to all users. The original message remains above its ordered replies.
Replies are plain text, escaped by Vue; no attachments or HTML are accepted.
There are no automatic email notifications in this version; users refresh the
conversation to check replies.

`users.is_admin` defaults to false. It is never accepted by profile, registration,
message or reply endpoints. Session authentication looks up the current user;
admin APIs also recheck the current database role. Hiding the navigation is not
an authorization boundary. User endpoints scope every thread lookup to its owner,
including reply writes; unauthorized thread IDs return 404. Admin endpoints return
403 for ordinary accounts. Cookie authentication and CSRF apply to every write.

After V14 migration, an operator can grant or revoke an existing verified active
account on the application host:

```sh
bash scripts/platform-admin.sh grant admin@example.com
bash scripts/platform-admin.sh revoke admin@example.com
```

The script changes a stable user ID, audits the role change, and fails if the email
does not identify exactly one active verified account. No automatic administrator
is created, and changing an account email does not transfer privileges to another
account. Refresh the console after a role change; API revocation applies to the
existing session on the next request.

API: GET/POST `/api/messages`, GET `/api/messages/{id}`, POST
`/api/messages/{id}/replies`. Admin read/reply routes have `/api/admin/messages`
prefix and never create a feedback thread on behalf of a user. Lists accept page
and size (5, 10, 50, 100; default 5). Detail returns the root and latest 50 replies
in ascending sequence, with `hasMore`; `before=<earliest sequence>` loads older
replies. List queries use a repeatable-read snapshot.

Each POST includes a client-generated UUID `clientId`. Repeating the same content
with that identifier is idempotent; changing content, thread or sender role returns
409. Server-derived sender roles cannot be spoofed in request JSON. A thread-row
lock serializes reply sequence and counters. Root creation locks its user's row.
Titles are limited to 240 UTF-8 bytes and bodies to 8,000 bytes. Limits are five
new threads/minute/user, 20 user replies/minute/user, 60 admin replies/minute/admin,
1,000 threads/user and 200 replies/thread. Rate-limit outages fail closed.
No raw message content is copied into audit logs. Feedback is account data,
independent of project analytics retention; account deletion anonymizes the owner
email via the existing account-deletion flow, without exposing the old email.

Verification: real PostgreSQL integration tests cover owner isolation, admin
permissions and revocation, CSRF, role spoofing, idempotency/conflicts, UTF-8 limits,
list/reply pagination, reply limits and concurrent reply ordering.
