# Deployment Runbook

Production deployment of the Roxstar backend to **Google Cloud Run** (`asia-south1`) with
**MongoDB Atlas** as the managed database.

| Setting | Value |
|---|---|
| GCP project | `roxstar-backend` |
| Region | `asia-south1` |
| Cloud Run service | `roxstar-backend` |
| Artifact Registry repo | `roxstar` |
| Secret | `roxstar-mongodb-uri` (Secret Manager) |
| Database | MongoDB Atlas (managed) — **not** the development compose container |

Every value above is overridable through GitHub repository variables; the defaults match this
project.

---

## 1. Why this shape

**One instance, deliberately.** Presence tracking, spin elimination timers and the per-room mutex
are in-process state (`presenceRegistry`, `spinScheduler`, `roomMutex`). A second instance would
split that state: two schedulers could drive one spin, and presence would be wrong. The service is
therefore pinned to `--min-instances=1 --max-instances=1`.

This is a deliberate trade-off, not an oversight. Horizontal scaling would need the Socket.IO Redis
adapter and a shared scheduler, which is explicitly outside the assessment's scope. The database
invariants (unique partial indexes, conditional updates) would still hold; only the in-memory
coordination would need replacing.

`--min-instances=1` also avoids scale-to-zero, which would drop live WebSocket connections. Even so,
a restart is survivable: `recoverSpins()` runs before the server accepts traffic and resumes any
`RUNNING` spin from persisted state.

**Cloud Run, specifically**, because it supports WebSockets natively, runs our existing Dockerfile
unchanged, terminates TLS for us, keeps every revision immutable (making rollback a traffic switch
rather than a rebuild), and integrates with Secret Manager.

---

## 2. One-time setup

### 2.1 MongoDB Atlas

1. Create a free **M0** cluster.
2. Create a database user with a strong generated password, scoped to `readWrite` on the
   `roxstar` database.
3. **Network access:** add `0.0.0.0/0`.

   > Cloud Run egresses from a dynamic address pool, so there is no stable IP to allowlist. The
   > security boundary is therefore SCRAM credentials plus TLS, both enforced by Atlas. The
   > production-grade alternative is a VPC connector with Cloud NAT and a reserved static IP,
   > which adds infrastructure beyond this assessment's scope. This is a documented,
   > deliberate demo-grade trade-off.

4. Copy the SRV connection string. It goes into Secret Manager in the next step and **never**
   into Git.

### 2.2 Google Cloud

```bash
export GITHUB_REPOSITORY="your-org/your-repo"
./infrastructure/cloudrun-setup.sh
```

The script enables the required APIs, creates the Artifact Registry repository, stores the Atlas
string in Secret Manager (read from stdin, so it stays out of shell history), creates the runtime
and deployer service accounts, grants least-privilege roles, and configures Workload Identity
Federation. It is idempotent.

### 2.3 GitHub configuration

From the script's output, add under **Settings → Secrets and variables → Actions**:

| Kind | Name | Value |
|---|---|---|
| Secret | `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full provider resource path |
| Secret | `GCP_DEPLOYER_SERVICE_ACCOUNT` | `roxstar-backend-deployer@…` |
| Variable | `GCP_PROJECT_ID` | `roxstar-backend` *(optional)* |
| Variable | `GCP_REGION` | `asia-south1` *(optional)* |

---

## 3. Secrets handling

| Secret | Stored in | Reaches the app as |
|---|---|---|
| MongoDB connection string | GCP Secret Manager | `MONGODB_URI` env var, mounted by Cloud Run |
| GCP deploy credentials | GitHub Actions secrets | Short-lived OIDC token, exchanged at run time |

Rules this setup enforces:

- **No long-lived cloud key exists.** Workload Identity Federation mints a token per workflow run,
  scoped by an attribute condition to this repository only. There is no JSON key to leak or rotate.
- **No secret is in Git.** `.gitignore` excludes `.env`; only `.env.example` with placeholders is
  tracked. Verify with `git log -p | grep -i mongodb+srv` — it should return nothing.
- **Least privilege.** The runtime account can read exactly one secret. The deployer can push
  images and deploy, nothing else.
- **Rotation:** `gcloud secrets versions add roxstar-mongodb-uri --data-file=-` then redeploy.
  The service references `:latest`, so the next revision picks it up.

---

## 4. Deploying

Normal path: **merge to `main`**. `deploy.yml` runs CI first (`workflow_call`), and deploys only
if it passes.

```
push to main
  └─ verify (CI: typecheck → lint → unit → integration w/ MongoDB → build)
       └─ deploy
            ├─ record currently serving revision      ← rollback target
            ├─ build image, tag :<sha> and :latest
            ├─ push to Artifact Registry
            ├─ gcloud run deploy
            ├─ smoke test the live URL
            └─ roll back automatically if the smoke test fails
```

Manual deploy: **Actions → Deploy to Cloud Run → Run workflow**.

### Production environment variables

| Variable | Value | Source |
|---|---|---|
| `NODE_ENV` | `production` | `--set-env-vars` |
| `PORT` | `8080` | **Injected by Cloud Run** — never set manually |
| `MONGODB_URI` | Atlas SRV string | `--set-secrets` from Secret Manager |
| `LOG_LEVEL` | `info` | `--set-env-vars` |
| `SPIN_ELIMINATION_INTERVAL_MS` | `5000` | `--set-env-vars` |

Configuration is validated by Zod at startup: a missing or malformed variable aborts the process
with a message naming every offender, so a misconfigured revision fails fast and never serves
traffic.

---

## 5. Health and readiness verification

The two endpoints are deliberately different and map to different probes:

| Endpoint | Meaning | Probe | On failure |
|---|---|---|---|
| `/health` | Process liveness. **Never queries MongoDB** | Liveness | Restart the container |
| `/ready` | Can serve traffic — requires a live database | Startup / readiness | Stop routing traffic; keep the process |

Cloud Run uses the startup probe on `/ready`, so a revision receives traffic only once it has
actually connected to Atlas.

### Verifying a deployment

```bash
URL=$(gcloud run services describe roxstar-backend --region asia-south1 --format='value(status.url)')

curl -sS "$URL/health"   # {"status":"ok",...}
curl -sS "$URL/ready"    # {"status":"ready","database":"connected",...}

# Full gate, including a real WebSocket upgrade through the ingress
cd backend && npm ci && node scripts/smoke.mjs "$URL"
```

The smoke test asserts liveness, database readiness, the 404 envelope, that anonymous sockets are
rejected (which itself proves the WebSocket upgrade completed), and that an authenticated socket
connects. Set `SMOKE_SKIP_WRITE=1` to skip the step that creates a throwaway user.

### Inspecting a running service

```bash
gcloud run services describe roxstar-backend --region asia-south1
gcloud run revisions list --service roxstar-backend --region asia-south1
gcloud run services logs read roxstar-backend --region asia-south1 --limit 100
```

---

## 6. Rollback

Cloud Run revisions are immutable, so rollback is a traffic switch — seconds, no rebuild.

```bash
# 1. Find the last known-good revision
gcloud run revisions list --service roxstar-backend --region asia-south1

# 2. Send all traffic back to it
gcloud run services update-traffic roxstar-backend \
  --region asia-south1 \
  --to-revisions roxstar-backend-<good-sha>=100

# 3. Confirm
curl -sS "$URL/health" && curl -sS "$URL/ready"
cd backend && node scripts/smoke.mjs "$URL"
```

The deploy workflow performs exactly this automatically when the smoke test fails, using the
revision it recorded before deploying. The faulty revision is left in place (with no traffic) so it
can be inspected.

### Alternative: redeploy a known-good image

```bash
gcloud run deploy roxstar-backend \
  --image asia-south1-docker.pkg.dev/roxstar-backend/roxstar/roxstar-backend:<good-sha> \
  --region asia-south1
```

### When rollback is *not* enough

Traffic switching reverts **code, not data**. The schema is additive-only (Phase 2 and Phase 4 added
fields with defaults and never removed or retyped one), so an older revision reads newer documents
safely. A future change that removes or retypes a field would need a forward fix instead — roll
forward, do not roll back.

An in-flight spin survives either direction: the new instance runs `recoverSpins()` at startup,
which derives outstanding eliminations from persisted state. Connected clients reconnect and receive
a fresh `room_state`.

---

## 7. WebSocket considerations

| Concern | Handling |
|---|---|
| Upgrade support | Native on Cloud Run over HTTPS → `wss://` |
| Connection lifetime | `--timeout 3600` (the default would cut long-lived sockets) |
| Instance count | `--max-instances 1` — required by in-process state |
| Cold starts dropping sockets | `--min-instances 1`; `recoverSpins()` covers restarts regardless |
| Polling-transport stickiness | `--session-affinity` enabled |
| Scaling adapter | None needed at one instance; Redis is explicitly out of scope |

Client connection is identical to local, with `wss://`:

```js
const socket = io('https://roxstar-backend-xxxx.a.run.app', { auth: { userId } });
```

---

## 8. Known limitations

- **Single instance.** Horizontal scaling requires the Socket.IO Redis adapter and a shared
  scheduler. Out of scope, and documented above.
- **Atlas allows all IPs.** A VPC connector with Cloud NAT is the production answer; credentials
  plus TLS are the boundary here.
- **Identity is a demo stand-in.** `X-User-Id` is not authentication. It is isolated in
  `middleware/currentUser.ts` and the socket handshake so a real mechanism could replace it without
  touching any service.
- **`min-instances=1` is not free-tier.** Roughly USD 5–10/month for the smallest configuration.
  Setting it to `0` removes that cost at the price of cold starts dropping live sockets.
