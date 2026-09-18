# Roxstar — Voice Draft, Room & Spin Wheel System

Implementation of the Roxstar candidate technical assessment: an Android voice-draft feature built on
Oboe, a Node.js real-time room service, and a multiplayer spin wheel.

Planning documents: [TASKS.md](TASKS.md) (requirement checklist with assessment points) and
[ARCHITECTURE.md](ARCHITECTURE.md) (system design, data model, event contracts, state machines).

## Status
**Phases 1–6 are complete and verified.**

| Area | Status | Verification Evidence |
|---|---|---|
| Repository skeleton, git, `.gitignore` | Done | Repository structure matches assessment |
| Backend: TypeScript, Express, error handling, config | Done | `npm run typecheck`, `npm run lint`, `npm run build` |
| Backend: Socket.IO server initialization | Done | All 7 mandatory room events implemented |
| Backend: MongoDB connection via Mongoose | Done | Verified against live instance & Atlas |
| `/health` and `/ready` endpoints | Done | Unit & smoke test verified (200 / 503) |
| Database models, indexes and repositories | Done | 8 models, 13 indexes, repository layer |
| Room REST API (create/join/leave/state/share draft) | Done | Services, DTOs, domain errors, idempotency |
| Socket.IO real-time events + presence | Done | All 7 mandatory room events, presence separation |
| Spin engine (server-authoritative, 5s eliminations, recovery) | Done | Absolute deadline timers, CAS winner, recovery |
| Concurrency & Correlation Logging (SP-15, BA-10) | Done | `x-request-id` tracing, atomic CAS, partial unique index |
| Test harness (Vitest + Supertest + socket.io-client) | Done | 60 unit tests, 155 integration tests |
| Dockerfile and local compose | Done | Image builds, non-root user, container reports healthy |
| CI/CD pipeline and Azure Container Apps deployment | Done | Deployed live, health/readiness/smoke verified, rollback rehearsed |
| Android app foundation & Compose UI | Done | Jetpack Compose UI (Record, Drafts, Room, Spin screens) |
| Native audio engine (Oboe C++ & JNI) | Done | AAudio/OpenSL ES lifecycle, real-time safety, 75 C++ unit tests |
| Real-time audio effects (Echo, Reverb, Pitch Shift) | Done | In-place DSP in Oboe callback, 3 selectable effects |
| Local Draft persistence & Playback | Done | `<id>.wav` + `<id>.json` storage, native Oboe playback |
| Room & Draft sharing integration | Done | OkHttp REST client, local draft metadata sharing |
| Realtime Room & Spin wheel UI | Done | Socket.IO client, animated canvas wheel, elimination sequence |
| Android build, lint, and unit testing | Done | 98 JVM unit tests (0 failures), `assembleDebug` (3 ABIs), `lintDebug` |
| System & API Documentation | Done | System architecture, audio flow, event flow, state machine, OpenAPI 3.0 |

All server-side features, cloud deployment, and Android client functionality are fully implemented and verified via automated test suites.

## Technology stack

| Layer | Choice |
|---|---|
| Runtime | Node.js 22 |
| Language | TypeScript (ESM, `NodeNext` module resolution) |
| HTTP framework | Express 5 |
| Realtime | Socket.IO |
| Database | MongoDB |
| ODM | Mongoose |
| Validation | Zod |
| Logging | Pino (`pino-http` for request logs) |
| Testing (Backend) | Vitest + Supertest |
| Container | Docker |
| Android Client | Kotlin + Jetpack Compose |
| Native Audio | C++17 + Oboe via NDK/CMake |
| Networking (Android) | OkHttp + Kotlinx Serialization + Socket.IO Java Client |

## Repository structure

```
android-app/          Android application (Compose UI, ViewModels, JNI, OkHttp, Socket.IO)
native-audio/         C++ Oboe engine (RecordingSession, PlaybackSession, effects, WavWriter)
backend/              Node.js + TypeScript service (Express 5, Socket.IO, Mongoose)
database/             Schema documentation and Mongoose models
infrastructure/       Azure Container Apps deployment, Dockerfile, docker-compose
docs/
  architecture/       System architecture, trade-offs, and assumptions
  audio/              Audio capture/playback flow, real-time safety, and DSP effects
  websocket/          Socket.IO event flow, presence, and payload contracts
  spin/               Spin wheel state machine and lifecycle specification
  openapi.yaml        OpenAPI 3.0 specification for all REST endpoints
  deployment.md       Azure deployment, secrets handling, and rollback runbook
TASKS.md              Requirement checklist and verification rubric
ARCHITECTURE.md       System design and data model
```

Backend unit and integration tests live in `backend/tests`, close to the code they cover. The
top-level `tests/` directory is reserved for cross-cutting tests that span the Android app and the
backend, per the structure recommended in the assessment.

## Prerequisites

- Node.js 22 or newer (`node -v`)
- Docker Desktop — used to run MongoDB locally and to build the backend image

## Environment variables

Copy the template and edit as needed:

```bash
cd backend
cp .env.example .env
```

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `NODE_ENV` | no | `development` | `development` \| `test` \| `production` |
| `PORT` | no | `3000` | HTTP port |
| `MONGODB_URI` | **yes** | — | MongoDB connection string |
| `LOG_LEVEL` | no | `info` | `fatal` \| `error` \| `warn` \| `info` \| `debug` \| `trace` |
| `SPIN_ELIMINATION_INTERVAL_MS` | no | `5000` | Milliseconds between eliminations; lowered in tests |

Configuration is validated at startup with Zod. A missing or malformed variable aborts the process
with a message naming every offending variable. `.env` is git-ignored and no real credentials are
committed — only `.env.example` with placeholders.

## Running MongoDB

```bash
docker compose -f infrastructure/docker-compose.yml up -d mongo
```

This exposes MongoDB on `localhost:27017`, matching the default `MONGODB_URI`. Stop it with
`docker compose -f infrastructure/docker-compose.yml stop mongo`.

## Running the backend

```bash
cd backend
npm install
npm run dev          # watch mode (tsx)
```

The backend **fails fast**: if MongoDB is unreachable at startup it logs the error and exits with
code 1 rather than serving traffic it cannot fulfil. Start MongoDB first.

## Available scripts

Run from `backend/`:

| Command | Purpose |
|---|---|
| `npm run dev` | Start in watch mode |
| `npm run build` | Compile TypeScript to `dist/` |
| `npm start` | Run the compiled build (requires `npm run build` first) |
| `npm test` | Unit tests — no database required |
| `npm run test:integration` | Integration tests — **requires MongoDB running** |
| `npm run test:all` | Both suites |
| `npm run test:watch` | Unit tests in watch mode |
| `npm run typecheck` | Type-check `src` and `tests` without emitting |
| `npm run lint` | Lint with ESLint |
| `npm run smoke -- <url>` | Post-deployment check: health, readiness, WebSocket upgrade |

## Endpoints

| Endpoint | Purpose | Responses |
|---|---|---|
| `GET /health` | **Liveness** — is the process running? Never queries MongoDB. | always `200 {"status":"ok","uptime":…,"timestamp":…}` |
| `GET /ready` | **Readiness** — can the service handle traffic? Checks the MongoDB connection. | `200 {"status":"ready","database":"connected"}` or `503` |

The two are deliberately separate. A deployment should wire its *liveness* probe to `/health` (restart
the process when it stops responding) and its *readiness* probe to `/ready` (stop routing traffic while
the database is down, without killing an otherwise healthy process). The container `HEALTHCHECK` in
`backend/Dockerfile` targets `/ready`, since Docker's single health signal governs traffic readiness.

Business endpoints (rooms, drafts, spins) arrive in later phases.

## Data model

Eight MongoDB collections (`User`, `Room`, `RoomMember`, `Draft`, `RoomDraftShare`, `Spin`,
`SpinParticipant`, `SpinEvent`), defined as Mongoose schemas in `backend/src/models/`. All queries live
in `backend/src/repositories/` — models carry structure and constraints only, never query logic.

Three invariants are enforced by **unique partial indexes**, so they hold even under concurrent
requests rather than depending on application-level checks:

| Invariant | Index |
|---|---|
| One active spin per room | `Spin{ roomId }` where `status ∈ {WAITING, RUNNING}` |
| One active membership per user per room | `RoomMember{ roomId, userId }` where `membershipState = JOINED` |
| Unique elimination order within a spin | `SpinParticipant{ spinId, eliminationOrder }` where the order is a number |

Indexes are **not** built implicitly: the connection sets `autoIndex: false` and `syncAllIndexes()`
runs explicitly at startup, before the server accepts traffic.

See [ARCHITECTURE.md](ARCHITECTURE.md) §3 for the full entity, relationship and index design.

## API

### Caller identity — demo mechanism, not authentication

Every endpoint except `POST /users` requires an **`X-User-Id`** header naming an existing user:

```bash
curl -X POST http://localhost:3000/users -H 'Content-Type: application/json'   -d '{"displayName":"Ada"}'
# -> {"id":"...","displayName":"Ada", ...}

curl -X POST http://localhost:3000/rooms -H "X-User-Id: <that id>"
```

> **This is an assessment/demo identity stand-in and provides no security.** There is no credential,
> signature or session, so any client can claim any identity. The assessment requires no
> authentication, so no JWT/session/OAuth infrastructure was introduced. It lives in one middleware
> (`src/middleware/currentUser.ts`) so a real authentication step could replace it without changing
> any service.

### Endpoints

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/users` | Create a user identity *(supporting)* | 201 |
| `POST` | `/drafts` | Register draft metadata *(supporting)* | 201 |
| `GET` | `/drafts` | List the caller's own drafts *(supporting)* | 200 |
| `POST` | `/rooms` | Create a room; caller becomes owner and first member | 201 |
| `GET` | `/rooms/:roomId` | Authoritative room snapshot — **members only** | 200 |
| `POST` | `/rooms/:roomId/join` | Join a room | **201** joined / **200** already a member |
| `POST` | `/rooms/:roomId/leave` | Leave a room | 200 |
| `POST` | `/rooms/:roomId/drafts` | Share one of your drafts into the room | **201** shared / **200** already shared |
| `POST` | `/rooms/:roomId/spins` | Start a spin — **room owner only**, 3–20 eligible members | 201 |
| `GET` | `/spins/:spinId` | Live spin state, or the final result plus its event sequence | 200 |

Join and Share are **idempotent**: a retry or duplicate tap returns 200 with the current state rather
than an error, and the unique partial indexes guarantee no duplicate row even under concurrent
requests.

### Real-time events (Socket.IO)

Connect with the same demo identity used by REST, then join a room you are already a member of:

```js
const socket = io('http://localhost:3000', { auth: { userId: '<your user id>' } });
socket.emit('join_room', { roomId }, (ack) => console.log(ack)); // { ok: true }
socket.on('room_state', (state) => { /* authoritative snapshot */ });
```

A socket never creates membership — join the room over REST first, or `join_room` returns
`NOT_A_MEMBER`.

| Event | When |
|---|---|
| `room_state` | On joining a room channel, and after any reconnect — the authoritative snapshot |
| `user_joined` | A member's **first** socket attaches, or a new member joins |
| `user_left` | `reason: 'LEFT'` (membership ended) or `'DISCONNECTED'` (last socket closed) |
| `draft_shared` | A draft is shared into the room |
| `spin_started` | A spin begins, with the eligible players |
| `user_eliminated` | Each elimination, with the updated remaining players |
| `winner_announced` | Exactly once, when one participant remains |

**Multiple connections per user are supported.** Presence is reference-counted per socket, so opening
a second tab emits no extra `user_joined`, and closing one emits no `user_left` while another remains.

**Synchronization rule.** Spin events carry a `sequenceNumber`. Apply an event when it follows the one
you hold; on a gap, adopt a fresh `room_state`; discard stale events. `room_state` always wins — the
event stream is not guaranteed gapless (see below).

### The spin

Server-authoritative throughout: the client never decides who is eliminated, and no client timer is
trusted. Only the room owner may start a spin, which requires **3-20** eligible members — every member
whose `membershipState` is `JOINED`, whether or not they are currently connected.

Once running, one participant is eliminated every **5 seconds** (`SPIN_ELIMINATION_INTERVAL_MS`) until
one remains and is recorded as the winner. Every event is persisted before it is broadcast.

| Situation | Behaviour |
|---|---|
| Two simultaneous start requests | A unique partial index allows exactly one; the other gets `409 ACTIVE_SPIN_EXISTS` |
| A member **leaves** mid-spin | Eliminated immediately (`eliminationReason: 'LEFT'`) |
| A member **disconnects** mid-spin | No effect on the spin — they can reconnect and resume |
| Members drop below 3 | The spin continues; 3-20 applies only at start |
| One participant remains | Spin completes with that winner |
| No participant remains | Spin aborts with no winner |
| The owner leaves or disconnects | The spin continues; ownership does not transfer |
| The server restarts mid-spin | The spin resumes, catching up the eliminations that fell due |

`eliminationReason` (`TIMER` or `LEFT`) exists because recovery counts only `TIMER` eliminations when
working out how many scheduled ticks are still owed. Counting a `LEFT` elimination would make recovery
skip a scheduled tick and end the spin early.

**No MongoDB transactions are used**, which keeps the standalone Docker setup. The trade-off is
documented rather than hidden: a crash between a state change and its event can leave a gap in the
event log, so sequence numbers are unique and monotonically increasing but **not gapless**, and
`room_state` is the repair mechanism.

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `MISSING_USER_ID` / `UNKNOWN_USER` | 401 | No `X-User-Id`, or it names no user |
| `INVALID_USER_ID` / `VALIDATION_ERROR` | 400 | Malformed id or body (with field details) |
| `NOT_A_MEMBER` | 403 | Caller is not an active member of the room |
| `DRAFT_NOT_OWNED` | 403 | Caller does not own the draft they tried to share |
| `ROOM_NOT_FOUND` / `DRAFT_NOT_FOUND` | 404 | No such room or draft |
| `ROOM_CLOSED` | 409 | Room no longer accepts the operation |
| `NOT_ROOM_OWNER` | 403 | Only the owner may start a spin |
| `SPIN_NOT_FOUND` | 404 | No such spin |
| `ACTIVE_SPIN_EXISTS` | 409 | The room already has an active spin |
| `INSUFFICIENT_PLAYERS` / `TOO_MANY_PLAYERS` | 409 | Eligible members outside the 3-20 range |

### Room state

`GET /rooms/:roomId` returns the authoritative snapshot. `activeSpin` is `null` when no spin is
running, and otherwise carries the spin's participants, remaining players, winner and
`lastSequenceNumber` — everything a client needs to resynchronize mid-spin.

```json
{
  "room": { "id": "...", "status": "ACTIVE", "ownerUserId": "...", "createdAt": "...", "updatedAt": "..." },
  "participants": [
    { "userId": "...", "displayName": "Ada", "membershipState": "JOINED",
      "connectionState": "DISCONNECTED", "joinedAt": "..." }
  ],
  "sharedDrafts": [
    { "draftId": "...", "name": "Take 1", "durationMs": 4200, "effect": "ECHO",
      "fileLocation": "/drafts/1.wav", "sharedByUserId": "...", "sharedAt": "..." }
  ],
  "activeSpin": null
}
```

Responses expose no Mongoose internals — no `_id`, no `__v` — and identifiers are strings.

### Known limitations

- The room **owner may leave** and the room stays `ACTIVE`; ownership does not transfer. If the owner
  is an active spin participant they are eliminated like anyone else, and the spin continues.
- **Presence is single-instance.** Socket reference counting lives in process memory, which is correct
  for one server. A multi-instance deployment would need the Socket.IO Redis adapter — out of scope
  for this assessment.
- The identity mechanism is a demo stand-in, not authentication (see above).

## Error format

Every error response uses one envelope, including the `/ready` 503:

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Route not found: GET /nope",
    "details": []
  }
}
```

`details` carries field-level entries for validation failures. Unexpected errors return a generic
`INTERNAL_ERROR` message — the stack trace goes to the logs only, never to the client.

## Running tests

### Backend tests

```bash
cd backend
npm test                 # 60 unit tests, no database needed
npm run test:integration # 155 integration tests, requires running MongoDB instance
npm run typecheck        # TypeScript typecheck
npm run lint             # ESLint
npm run build            # Production TypeScript build
```

- **Unit (60 tests in 10 files)**: Covers `/health`, `/ready` (both branches), domain errors, Zod request validations, in-memory SpinScheduler (15 tests), SpinRecovery (9 tests), and WebSocket handlers.
- **Integration (155 tests in 8 files)**: Validates Mongoose schema constraints, compound/partial indexes, atomic CAS operations, concurrent joins/spins (SP-15), and correlation logging (BA-10).

### Android tests & verification

```bash
cd android-app
./gradlew testDebugUnitTest  # 98 unit tests across JVM reducers, repositories, and network clients
./gradlew assembleDebug      # Builds debug APK and compiles native C++ libraries for 3 ABIs
./gradlew lintDebug          # Android lint analysis (0 errors)
```

- **Unit (98 tests, 0 failures)**: Covers `RecordingReducerTest` (26), `DraftRepositoryTest` (13), `PlaybackReducerTest` (12), `RoomApiClientTest` (10), `RoomSocketClientTest` (9), `SpinReducerTest` (8), `NativeContractTest` (7), `RecordingCleanupTest` (7), `RoomUiStateTest` (3), `AudioStatusMessageTest` (3).
- **Compilation**: Gradle CMake integration cross-compiles native audio libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

### Native audio tests (Host C++)

```bash
cd native-audio/tests/build
./native_audio_tests.exe     # 75 host C++ tests
```

- **Host C++ (75 tests, 0 failures)**: Tests `RingBuffer` lock-free circular buffer (7), `WavWriter` (8), `WavReader` (13), `PlaybackBuffer` (8), `RecordingSession` lifecycle and cancellation (7), effect factory (5), and DSP implementations for `EchoEffect` (6), `ReverbEffect` (6), and `PitchShiftEffect` (9), plus end-to-end processing pipeline integration (5).


## Building for production

```bash
cd backend
npm run build
npm start
```

## Deployment

The backend deploys to **Azure Container Apps** (`centralindia`) with **MongoDB Atlas** as the managed
production database. Full instructions, secrets handling and the rollback runbook are in
**[docs/deployment.md](docs/deployment.md)**.

```
push to main → CI (typecheck, lint, unit, integration, build)
             → build image, push only the immutable :<commit-sha> tag to ACR
             → az containerapp update --image
             → assert replicas are still 1/1
             → smoke test (/health, /ready, real WebSocket upgrade)
             → all gates passed  → promote :latest to this image
             → any gate failed   → restore the previous image and verify it
```

Deployed and verified live, including a rehearsed rollback — see
[docs/deployment.md §9](docs/deployment.md#9-deployment-evidence) for the evidence.

One-time setup (registry names are globally unique, so pick one):

```bash
export GITHUB_REPOSITORY="your-org/your-repo"
export ACR_NAME="roxstaracr$RANDOM"
./infrastructure/azure-setup.sh   # registry, pull identity, environment, app, OIDC
```

Verify any deployment, local or hosted:

```bash
cd backend && npm run smoke -- https://<your-service-url>
```

### Production configuration

| Variable | Value | Source |
|---|---|---|
| `NODE_ENV` | `production` | Container App env var |
| `PORT` | `3000` | **Set explicitly** — Azure does not inject it; must match `targetPort` |
| `MONGODB_URI` | Atlas SRV string | **Container Apps secret** — never in Git |
| `LOG_LEVEL` | `info` | Container App env var |
| `SPIN_ELIMINATION_INTERVAL_MS` | `5000` | Container App env var |

Azure access from CI uses **GitHub OIDC federation**, so no Azure client secret exists, and image
pulls use a managed identity, so no registry password exists either. `.env` is git-ignored; only
`.env.example` is tracked.

### Why exactly one replica

The app runs with `minReplicas: 1, maxReplicas: 1`, and the deploy workflow **asserts that after every
deployment**. Presence tracking, spin timers and the per-room mutex are in-process, so a second
replica would split that state. Scaling horizontally would need the Socket.IO Redis adapter and a
shared scheduler — out of scope here, and recorded as a known limitation rather than hidden.

## Docker

```bash
# Build the backend image
docker build -t roxstar-backend ./backend

# Or run backend + MongoDB together
docker compose -f infrastructure/docker-compose.yml up

# If host port 3000 is already taken, publish the backend elsewhere
BACKEND_PORT=3200 docker compose -f infrastructure/docker-compose.yml up
```

The backend container declares a `HEALTHCHECK` against `/ready`, so `docker ps` shows it as `healthy`
only once it has actually connected to MongoDB.
