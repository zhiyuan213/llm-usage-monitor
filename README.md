# MeterTrail

LLM observability and cost tracking, built from **MeterTrail Project Design V1.1**. Vue 3 + TypeScript, Spring Boot 3.5 / Java 17, PostgreSQL 16, Redis, and Docker Compose. The interface defaults to English and supports Simplified Chinese.

This repository contains a runnable development release, not a production readiness certification. The verified local capabilities and remaining launch gates are tracked in [implementation status](docs/implementation-status.md).

Node.js / TypeScript, Python, and Java SDKs: [llm-usage-monitor-sdk](https://github.com/zhiyuan213/llm-usage-monitor-sdk). See [SDK installation and usage](docs/sdk.md).

## Start locally

Prerequisites: Docker Desktop (Linux containers) / Docker Engine with Compose v2. Node.js 24 is needed only for local frontend development and smoke scripts; Java 17 / Maven 3.9 for local backend development.

```powershell
Copy-Item .env.example .env
# Replace APP_SECRET (at least 32 random characters) and DATABASE_PASSWORD.
# Keep SECURE_COOKIES=false only for local HTTP development.
docker compose up --build -d
```

- Application: <http://localhost:8088>
- Development mailbox: <http://localhost:8025>
- PostgreSQL, Redis, and Actuator are internal to the Docker network.
- Create an account, open its verification message in Mailpit, then log in.
- Create a project and an API key. A new email verification is required for key operations. The 60-second resend cooldown applies across actions for an account.
- Configure model rates in Project settings if you want estimated costs. No live model prices are invented or fetched implicitly.

`.env`, local credentials, generated data, caches, and build outputs are ignored by Git. The example email server is for development only. Production needs real SMTP, HTTPS with secure cookies, and a reviewed environment configuration.

Tencent Cloud SES API template delivery is also supported, including personal accounts without SMTP access. See [腾讯云邮件推送接入](docs/tencent-ses.zh-CN.md) for approved templates, restricted credentials and server update steps.

### Send a complete example

```powershell
$env:METERTRAIL_ENDPOINT = 'http://localhost:8088'
$env:METERTRAIL_API_KEY = '<your one-time-displayed project key>'
node scripts/send-example.mjs
```

The script emits an explicit Trace, an Observation, and a Score: **3 units**. Model usage is illustrative example data; real integrations must use actual usage returned by the model provider. View the results in Overview and Traces.

For the file-based REST examples:

```sh
node scripts/generate-example.mjs
bash examples/rest/curl.sh
python examples/rest/python.py
node examples/rest/node.mjs
java examples/rest/Send.java
go run examples/rest/main.go
dotnet run --project examples/rest/csharp/Send.csproj
php examples/rest/send.php
```

Run from the repository root. File-based examples reuse the generated identities so repeated submissions are duplicates. Regenerate the file for a genuinely new request. See [SDK guide](docs/sdk.md) for production retry behavior and source package installation. No SDK is published to a package registry yet.

## Repository layout

| Directory | Purpose |
| --- | --- |
| `backend/` | Spring Boot API, transactional ingestion, worker, Flyway migrations, PostgreSQL integration tests |
| `frontend/` | Vue console, marketing pages, authentication flows, bilingual UI |
| `sdk/` | Node.js/TypeScript, Python, and Java source SDKs |
| `examples/rest/` | cURL, Python, Node.js, Java, Go, C#, PHP |
| `ops/` | Nginx routing, metrics, Grafana, release gates, backup and restore, load tests |
| `scripts/` | End-to-end tests, synthetic release smoke, monitoring gate, example generation |
| `docs/` | Protocol, architecture, operations, status and validation evidence |

## Development and verification

```sh
# Requires a running Docker daemon for Testcontainers.
mvn -B -f backend/pom.xml verify
mvn -B -f sdk/java/pom.xml verify

cd frontend
npm ci
npm test
npm run build
npm audit --audit-level=high
cd ..

node --test sdk/node/test.mjs
python -m unittest discover -s sdk/python -p 'test_*.py'

# Requires the full local Compose stack and Mailpit; takes at least 60s
# because it obeys the real verification cooldown.
node scripts/e2e.mjs
```

On recent Docker engines that require a newer API version, add `-Dapi.version=1.44` to Maven (quote the argument in PowerShell). The test does not skip PostgreSQL tests when Docker is missing.

Local frontend: `npm run dev` inside `frontend` proxies API calls to port 8080. The backend uses `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `REDIS_HOST`, and `APP_SECRET`. Use the same origin for frontend/API; CORS is not broadly enabled.

## Security and accounting

- Project keys have 256 random bits, `events:write` semantics, one-time disclosure, and SHA-256 storage. Every request reads current key state; revocation has no stale auth cache.
- Passwords use bcrypt. Console sessions are revocable opaque credentials in HttpOnly cookies; writes require a session-bound CSRF token. Resetting passwords and changing emails revoke existing sessions.
- Verification codes are bound to account/action/target, HMAC-protected, valid for 5 minutes, and limited to 5 attempts. Email outbox content is AES-GCM encrypted at rest and cleared after delivery.
- Ingestion locks the shared workspace row before reserving units. Durable event identity and object identity are separate. Acknowledgement happens after commit.
- Workers claim PostgreSQL rows with `FOR UPDATE SKIP LOCKED`, complete each batch transactionally, and recover automatically after connection loss. Poison batches are isolated; audited replay and email-verified, idempotent reservation compensation are available. Current queries use canonical objects, avoiding duplicate aggregate increments.
- Missing model prices or usage remain unknown. Cached input is a subset of input. Model costs and platform unit charges are separate ledgers.
- PostgreSQL retains original object identity independently of visible analytics detail. Processed transport events and batch results are retained for at least 8 days.

See [protocol](docs/protocol.md), [architecture](docs/architecture.md), and [operations](docs/operations.md) for precise behavior and limitations.

## External integrations

Configure GitHub OAuth with a callback of `<PUBLIC_URL>/api/auth/github/callback`. Existing users with a matching email must log in and explicitly link GitHub; accounts are never silently merged. GitHub accounts without a verified email must use email registration and then link GitHub.

Paddle defaults to its sandbox API. Configure API key, webhook secret, and Core/Pro price IDs. The backend creates hosted checkout transactions, verifies raw webhook signatures, deduplicates notifications, protects subscription updates against stale events, records transaction/refund evidence, and reconciles known subscriptions hourly. Successful frontend redirects never activate plans. **Live overage collection, full merchant sandbox acceptance, and pending subscription policy decisions remain launch gates.**

## Monitoring and deployment

```sh
# Set GRAFANA_PASSWORD in .env first.
docker compose -f compose.yml -f compose.monitoring.yml up -d
```

Grafana is bound to <http://localhost:3001>. The three provisioned dashboards cover health, ingestion/backlog, and accounting correctness. Alertmanager has no external receiver until the operator configures one.

CI checks tests, frontend compilation, source examples, dependency vulnerabilities, secrets, and builds immutable image artifacts. `ops/release.sh` requires an explicitly approved SHA, image digest, synthetic tenant, candidate endpoint, and monitoring. It fails closed for unavailable metrics and retains the previous slot throughout the 15-minute observation window. Production DNS, TLS, cloud deployment, and paid resources have not been configured by this repository.

## 中文说明

这是根据 V1.1 文档开发的可运行版本，代码包含前后端、真实数据库、源码 SDK、测试和运维配置。默认英文，可在右上角切换简体中文；偏好保存在本地，不随浏览器语言自动变化。

本地启动后，使用 Mailpit 完成邮箱验证，不需要真实邮件供应商。所有控制台业务读取后端真实数据，未接入数据时显示空状态；官网预览明确标注为示意数据。生产商户审核、正式支付验收、完整容量测试、跨机恢复演练及文档中待确认的商业规则仍需在上线前完成，详细清单见 `docs/implementation-status.md`。
