# Deployment Runbook

Production deployment of the Roxstar backend to **Azure Container Apps** (`centralindia`)
with **MongoDB Atlas** as the managed database.

| Setting | Value |
|---|---|
| Resource group | `roxstar-backend-rg` |
| Region | `centralindia` |
| Container App | `roxstar-backend` |
| Container Apps environment | `roxstar-env` |
| Container registry | set by you — **globally unique**, e.g. `roxstaracr1234` |
| Pull identity | `roxstar-backend-id` (user-assigned managed identity) |
| Deployer | `roxstar-github-deployer` (Entra app, OIDC federated) |
| Database | MongoDB Atlas — **not** the development compose container |

Every value is overridable through GitHub repository variables.

---

## 1. Why this shape

**One replica, deliberately.** `presenceRegistry`, `spinScheduler` and `roomMutex` hold in-process
state. A second replica would split it — two schedulers could drive the same spin, and presence would
be wrong. The app is pinned to `minReplicas: 1, maxReplicas: 1`, and the deploy workflow **asserts
that after every deployment** rather than trusting it.

Scaling out would need the Socket.IO Redis adapter and a shared scheduler, which is outside this
assessment's scope. The database invariants (unique partial indexes, conditional updates) would still
hold; only the in-memory coordination would need replacing.

**Single revision mode, not traffic splitting.** Multi-revision traffic splitting would run two
revisions concurrently, contradicting the single-replica rule. Rollback is therefore an image
restore, which is deterministic and needs no rebuild because registry images are immutable.

**No secrets anywhere.** GitHub authenticates to Azure by OIDC federation, so no Azure client secret
exists. The registry's admin user is disabled, so no registry password exists — Container Apps pulls
using a managed identity holding `AcrPull` on that one registry.

**Azure Container Apps, specifically**, because it supports WebSockets natively, runs the existing
Dockerfile unchanged, terminates TLS, supports HTTP probes on `/health` and `/ready`, and sends
`SIGTERM` for graceful shutdown — which is what lets spin timers stop cleanly and `recoverSpins()`
resume them on the next revision.

---

## 2. One-time setup

### 2.1 MongoDB Atlas

Already configured. Two things must remain true:

1. A database user with `readWrite` on the `roxstar` database.
2. **Network access:** `0.0.0.0/0`.

   > Container Apps egresses from a dynamic address pool, so there is no stable IP to allowlist. The
   > security boundary is therefore SCRAM credentials plus TLS. The production-grade alternative is a
   > VNet-integrated environment with a NAT gateway and a static outbound IP, which adds
   > infrastructure beyond this assessment's scope. A documented, deliberate trade-off.

### 2.2 Azure

Registry names are globally unique across all of Azure, so you must choose one:

```bash
export GITHUB_REPOSITORY="your-org/your-repo"
export ACR_NAME="roxstaracr$RANDOM"          # 5-50 alphanumeric, no hyphens
./infrastructure/azure-setup.sh
```

The script is idempotent and creates: the registry (admin user disabled), the pull identity with
`AcrPull`, the Container Apps environment, the Container App itself from
`infrastructure/containerapp.yaml`, the deployer app registration, **both** federated credentials, and
the two narrowly scoped role assignments.

It prompts for the Atlas connection string with **hidden input** and renders it into a temporary file
(mode 600, deleted on exit) that `az containerapp create --yaml` consumes. The string never touches
the repository or your shell history.

> **Expected:** the first revision runs a placeholder image and reports unhealthy, because the
> placeholder does not serve `/ready`. The first deployment replaces it. This is not an error.

### 2.3 GitHub configuration

Create an **environment named `production`** (Settings → Environments). `deploy.yml` declares it, and
one federated credential is bound to that subject — without the environment, the job cannot
authenticate.

Then add what the script prints:

| Kind | Name | Value |
|---|---|---|
| Secret | `AZURE_CLIENT_ID` | Deployer app id |
| Secret | `AZURE_TENANT_ID` | Directory tenant id |
| Secret | `AZURE_SUBSCRIPTION_ID` | Subscription id |
| Variable | `ACR_NAME` | **Required** — no safe default exists |
| Variable | `AZURE_RESOURCE_GROUP` | `roxstar-backend-rg` *(optional)* |
| Variable | `AZURE_REGION` | `centralindia` *(optional)* |
| Variable | `CONTAINERAPP_NAME` | `roxstar-backend` *(optional)* |

Those three ids are identifiers rather than credentials — they grant nothing without the federation
binding — but are stored as secrets by convention.

---

## 3. Secrets handling

| Secret | Stored in | Reaches the app as |
|---|---|---|
| MongoDB connection string | Container Apps secret `mongodb-uri` | `MONGODB_URI`, via `secretRef` |
| Azure deploy credentials | **None exist** | Short-lived OIDC token per workflow run |
| Registry credentials | **None exist** | Managed identity with `AcrPull` |

Rules this enforces:

- **No long-lived Azure credential exists.** Federation mints a token per run, scoped by subject to
  this repository. Nothing to leak or rotate.
- **No secret is in Git.** `.gitignore` excludes `.env`; only `.env.example` with placeholders is
  tracked. `infrastructure/containerapp.yaml` is a template whose `__MONGODB_URI__` placeholder is
  filled at render time.
- **Least privilege.** The pull identity holds `AcrPull` on one registry. The deployer holds
  `AcrPush` on that registry and `Container Apps Contributor` on **the single app resource** — not the
  resource group, not the subscription.
- **Rotation:**
  ```bash
  az containerapp secret set --name roxstar-backend \
    --resource-group roxstar-backend-rg --secrets mongodb-uri=<new-value>
  az containerapp revision restart --name roxstar-backend \
    --resource-group roxstar-backend-rg --revision <current>
  ```

---

## 4. Deploying

Normal path: **merge to `main`**. `deploy.yml` runs CI first (`workflow_call`) and deploys only if it
passes.

```
push to main
  └─ verify (CI: typecheck → lint → unit → integration w/ MongoDB → build → audit)
       └─ deploy
            ├─ azure/login@v2 (OIDC, no secret)
            ├─ record currently deployed image        ← rollback target
            ├─ build + push  :<commit-sha>  and  :latest
            ├─ az containerapp update --image :<sha>
            ├─ assert replicas are still 1/1 and mode is Single
            ├─ poll /ready until the new revision serves
            ├─ smoke test (/health, /ready, WebSocket upgrade)
            └─ on any failure → restore the previous image
```

Manual deploy: **Actions → Deploy to Azure Container Apps → Run workflow**.

### Production environment variables

| Variable | Value | Source |
|---|---|---|
| `NODE_ENV` | `production` | Container App env var |
| `PORT` | `3000` | **Set explicitly** — see below |
| `MONGODB_URI` | Atlas SRV string | Container Apps secret `mongodb-uri` |
| `LOG_LEVEL` | `info` | Container App env var |
| `SPIN_ELIMINATION_INTERVAL_MS` | `5000` | Container App env var |

> **`PORT` is not injected by Azure.** Unlike Cloud Run, Container Apps does not set `PORT`; the
> platform routes to whatever `targetPort` says. `PORT=3000` and `targetPort: 3000` must agree, and
> both are set in `infrastructure/containerapp.yaml`. Changing one without the other breaks ingress.

Configuration is validated by Zod at startup, so a missing or malformed variable aborts the process
with a message naming every offender — the revision then fails its startup probe and never serves.

---

## 5. Health and readiness verification

| Endpoint | Meaning | Probe | On failure |
|---|---|---|---|
| `/health` | Process liveness. **Never queries MongoDB** | Liveness | Restart the container |
| `/ready` | Can serve traffic — requires a live database | Startup + Readiness | Stop routing traffic; keep the process |

The startup probe gates the revision on `/ready`, so a revision receives traffic only after it has
connected to Atlas and `recoverSpins()` has run.

```bash
RG=roxstar-backend-rg
URL="https://$(az containerapp show -n roxstar-backend -g $RG \
  --query properties.configuration.ingress.fqdn -o tsv)"

curl -sS "$URL/health"   # {"status":"ok",...}
curl -sS "$URL/ready"    # {"status":"ready","database":"connected",...}

cd backend && npm ci && node scripts/smoke.mjs "$URL"
```

The smoke test asserts liveness, database readiness, the 404 envelope, that anonymous sockets are
rejected (which proves the WebSocket upgrade completed), and that an authenticated socket connects.
`SMOKE_SKIP_WRITE=1` skips the step that creates a throwaway user.

### Inspecting a running app

```bash
az containerapp show     -n roxstar-backend -g $RG -o yaml
az containerapp revision list -n roxstar-backend -g $RG -o table
az containerapp logs show     -n roxstar-backend -g $RG --follow
```

---

## 6. Rollback

Images are immutable and commit-addressed, so rollback restores a known-good image:

```bash
# 1. Find the last known-good tag (or read it from the failed run's summary)
az acr repository show-tags --name <acr> --repository roxstar-backend --orderby time_desc -o table

# 2. Restore it
az containerapp update -n roxstar-backend -g roxstar-backend-rg \
  --image <acr>.azurecr.io/roxstar-backend:<good-sha>

# 3. Confirm
curl -sS "$URL/health" && curl -sS "$URL/ready"
cd backend && node scripts/smoke.mjs "$URL"
```

The workflow performs exactly this automatically when readiness polling or the smoke test fails,
using the image tag recorded before the deploy. The failed image stays in the registry for inspection.

**Traffic splitting is deliberately not used.** It would require multi-revision mode, which runs two
revisions at once and contradicts the single-replica requirement.

### When rollback is *not* enough

Restoring an image reverts **code, not data**. The schema is additive-only (Phases 2 and 4 added
fields with defaults and never removed or retyped one), so an older image reads newer documents
safely. A change that removes or retypes a field would need a forward fix instead.

An in-flight spin survives either direction: the new revision runs `recoverSpins()` at startup, which
derives outstanding eliminations from persisted state. Connected clients reconnect and receive a fresh
`room_state`.

---

## 7. WebSocket considerations

| Concern | Handling |
|---|---|
| Upgrade support | Native on Container Apps over HTTPS → `wss://` |
| Transport | `transport: auto` negotiates HTTP/1.1, which the upgrade needs |
| Replica count | `maxReplicas: 1` — required by in-process state, asserted on every deploy |
| Ingress idle timeout | Socket.IO's ~25 s heartbeat keeps connections non-idle. **Verify with a long-held connection on first deploy** |
| Sticky sessions | `affinity: sticky` — redundant at one replica, harmless and correct if that ever changes |
| Scaling adapter | None needed at one replica; Redis is explicitly out of scope |

```js
const socket = io('https://<app>.<region>.azurecontainerapps.io', { auth: { userId } });
```

---

## 8. Known limitations

- **Single replica.** Scaling out needs the Socket.IO Redis adapter and a shared scheduler.
- **Brief revision overlap.** During a deploy, Container Apps may run the old and new revisions
  momentarily. Two schedulers could therefore tick the same spin for a few seconds. The database
  invariants are the backstop — conditional `status:'ACTIVE'` updates, unique elimination order,
  unique event sequence and CAS completion mean no participant can be eliminated twice and no winner
  announced twice. Bounded and safe, but worth knowing.
- **Atlas allows all IPs.** VNet integration with a NAT gateway is the production answer.
- **Identity is a demo stand-in.** `X-User-Id` is not authentication. It is isolated in
  `middleware/currentUser.ts` and the socket handshake so a real mechanism could replace it.
- **`minReplicas: 1` means always-on billing** beyond the Container Apps free grant. Setting `0`
  removes the cost but drops live WebSocket connections and idles the spin scheduler.
