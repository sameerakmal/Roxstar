# Roxstar — Voice Draft, Room & Spin Wheel System

Implementation of the Roxstar candidate technical assessment: an Android voice-draft feature built on
Oboe, a Node.js real-time room service, and a multiplayer spin wheel.

Planning documents: [TASKS.md](TASKS.md) (requirement checklist with assessment points) and
[ARCHITECTURE.md](ARCHITECTURE.md) (system design, data model, event contracts, state machines).

## Status

This repository is being built in phases. **Phase 1 (project and backend foundation) is complete.**

| Area | Status |
|---|---|
| Repository skeleton, git, `.gitignore` | Done |
| Backend: TypeScript, Express, error handling, config | Done |
| Backend: Socket.IO server initialization | Done (connection logging only — no room or spin events yet) |
| Backend: MongoDB connection via Mongoose | Done, verified against a live instance (connection layer only — **no models yet**) |
| `/health` and `/ready` endpoints | Done |
| Test harness (Vitest + Supertest) | Done — 6 tests |
| Dockerfile and local compose | Done — image builds, stack runs, container reports healthy |
| Rooms, drafts, spin wheel, Android app, Oboe audio, CI/CD, cloud deploy | **Not started** |

Nothing in the room, draft, spin, or Android sections of the assessment is implemented yet.

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
| Testing | Vitest + Supertest |
| Container | Docker |
| Android (later phase) | Kotlin + Oboe via NDK |

## Repository structure

```
android-app/          Android application (later phase)
native-audio/         C++ Oboe engine (later phase)
backend/              Node.js + TypeScript service
database/             Schema documentation and migrations (later phase)
infrastructure/       docker-compose and deployment assets
docs/
  architecture/       system architecture diagram
  audio/              audio-flow diagram
  websocket/          room and event-flow diagram
  spin/               spin state-machine diagram
tests/                cross-cutting Android/native/e2e tests (later phase)
TASKS.md              requirement checklist
ARCHITECTURE.md       system design
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
| `npm test` | Run the test suite once |
| `npm run test:watch` | Run tests in watch mode |
| `npm run typecheck` | Type-check `src` and `tests` without emitting |
| `npm run lint` | Lint with ESLint |

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

```bash
cd backend
npm test
```

The suite covers `/health`, both `/ready` branches, 404 handling, the error envelope, and a Socket.IO
connection smoke test. It needs **no running MongoDB** — the Express app is constructed without a
database connection, and readiness is tested against a stubbed connection state.

## Building for production

```bash
cd backend
npm run build
npm start
```

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
