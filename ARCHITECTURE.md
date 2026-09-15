# ARCHITECTURE.md — Roxstar Voice Draft, Room & Spin Wheel System

Architecture for the Roxstar Candidate Technical Assessment. The entire backend described here is
implemented: foundation and data model (§1-§3), room APIs (§4), WebSocket events and presence (§5-§6),
the spin engine (§7) and the deployment pipeline (§8). The Android client in §1.2 is the remaining
unimplemented component.

Scope boundaries are taken from the assessment: live audio streaming, WebRTC, LiveKit, AI, Kubernetes
and multi-tenant architecture are **out of scope**. The spin wheel uses virtual points only; no
payment or wallet logic is implemented.

---

## 1. System architecture

### 1.1 Components

| Component | Responsibility | Technology |
|---|---|---|
| Android app | Recording UI, Draft library, room UI, spin UI; REST client and WebSocket client | Android (Kotlin) + JNI |
| Native audio engine | Microphone capture, effect processing, file writing, playback | C++ with Oboe via NDK/CMake |
| Node.js service | REST APIs, WebSocket gateway, room state, spin engine, validation, persistence, broadcasting | Node.js 22 + TypeScript, Express 5, Socket.IO |
| Database | Users, rooms, memberships, drafts, spins, spin participants, spin events | MongoDB with Mongoose (see §9) |
| Container image | Reproducible backend package | Docker |
| Cloud host | Final hosted endpoint | Google Cloud Run (`asia-south1`) + MongoDB Atlas (see §8) |

### 1.2 Topology

```
┌──────────────────────────── Android device ────────────────────────────┐
│                                                                        │
│   UI layer (screens: Record, Drafts, Room, Spin)                       │
│        │                                                               │
│   ViewModel / state layer                                              │
│        │                        │                                      │
│   Repository layer              │                                      │
│        │                        │                                      │
│   ┌────┴─────────┐    ┌─────────┴──────────┐   ┌──────────────────┐    │
│   │ Native audio │    │ REST client        │   │ WebSocket client │    │
│   │ engine (JNI) │    └─────────┬──────────┘   └────────┬─────────┘    │
│   │  Oboe        │              │                       │              │
│   └──────┬───────┘              │                       │              │
│          │                      │                       │              │
│   Local Draft storage           │                       │              │
│   (audio files + metadata)      │                       │              │
└─────────────────────────────────┼───────────────────────┼──────────────┘
                                  │ HTTPS                 │ WSS
                                  │ (REST)                │ (Socket.IO)
┌─────────────────────────────────┼───────────────────────┼──────────────┐
│                      Node.js backend (container)        │              │
│                                 │                       │              │
│   ┌─────────────────────────────┴───────┐  ┌────────────┴──────────┐   │
│   │ REST layer                          │  │ WebSocket gateway     │   │
│   │  routes → controllers → validation  │  │  connection registry  │   │
│   └─────────────────┬───────────────────┘  │  room broadcasting    │   │
│                     │                      └────────────┬──────────┘   │
│                     ▼                                   │              │
│   ┌──────────────────────────────────────────────────────────────┐     │
│   │ Service layer (authoritative state owner)                    │     │
│   │   RoomService │ DraftService │ SpinEngine │ EventPublisher    │     │
│   └──────────────────────────┬───────────────────────────────────┘     │
│                              ▼                                         │
│   ┌──────────────────────────────────────────────────────────────┐     │
│   │ Repository layer (persistence)                               │     │
│   └──────────────────────────┬───────────────────────────────────┘     │
└──────────────────────────────┼─────────────────────────────────────────┘
                               ▼
                        ┌─────────────┐
                        │  Database   │
                        └─────────────┘
```

### 1.3 Key architectural decisions

**The backend is the single source of truth.** Section B1 requires authoritative room state and C3
requires room state consistency. Clients never compute room membership, spin progress, eliminations
or the winner — they render what the server tells them. Every mutation goes REST → service layer →
database → broadcast, so the broadcast is always a statement about persisted state.

**WebSocket carries events, not audio.** The technology guide is explicit: the socket transport
handles presence, room state, draft sharing, spin progress, eliminations and winner notifications,
and does not carry live audio. Audio stays local to the device; only Draft *metadata and hosted file
location* cross the wire.

**One writer per room.** Room and spin mutations for a given room are serialized so concurrent joins,
duplicate start requests and mid-spin departures cannot interleave into inconsistent state. This one
decision covers several C4 edge cases (duplicate start requests, simultaneous joins).

**The spin engine is a separate service module.** It owns the timer, the state machine and the
elimination sequence, and depends on repositories and an event publisher rather than on HTTP or
socket objects. This keeps the 50-point section unit-testable with a controllable clock.

**Persist-then-broadcast.** Every spin event is written to the event log before it is emitted, so the
persisted sequence and the broadcast sequence agree, the Get Spin Result API can replay the true
order, and a restart has authoritative state to recover from.

---

## 2. Repository structure

Follows the assessment's recommended structure, with the contents each directory is proposed to hold.

```
/android-app              Android application (Kotlin): UI, ViewModels,
                          repositories, REST client, WebSocket client,
                          local Draft storage
/native-audio             C++ Oboe engine: input stream lifecycle, effect
                          processor, file writer, playback, JNI bridge
/backend                  Node.js + TypeScript service
    /src
        /config           environment configuration, database connection
        /models           Mongoose schemas, enums, indexes, validators
        /routes           HTTP route definitions
        /controllers      request handling, response shaping
        /services         RoomService, DraftService, SpinEngine,
                          EventPublisher
        /repositories     all database queries; the only layer that
                          touches models
        /websocket        gateway, connection registry, event emitters
        /middleware       request logging, validation, 404, error handler
        /errors           AppError and error envelope types
        /utils            logger
        app.ts            Express app (no database, no listen)
        server.ts         startup: connect, attach Socket.IO, listen
/database                 schema definition, migrations, seed data,
                          entity-relationship diagram
/infrastructure           Dockerfile, local compose file, cloud deployment
                          assets, environment templates
/docs
    /architecture         system architecture, audio-flow, event-flow and
                          spin state-machine diagrams; assumptions,
                          edge cases, trade-offs, known limitations
    /api                  OpenAPI/Swagger specification
/tests                    unit and integration tests
README.md                 setup, run, test and deployment instructions
```

CI/CD configuration lives in the repository root convention directory for the chosen CI provider;
the pipeline definition itself is listed in the submission package alongside the Docker assets.

---

## 3. Database entities

Entity set is the assessment's suggested list (D2). Each entity is a MongoDB collection with a Mongoose
schema. Each purpose below restates the assessment's stated purpose, then proposes fields and indexes.

Every document has a Mongoose `_id` (ObjectId) primary key; `createdAt` / `updatedAt` come from schema
timestamps. References below are ObjectId fields declared with `ref`, resolved by explicit population
rather than joins.

### 3.1 Entity summary

| Entity | Assessment purpose | Proposed key fields |
|---|---|---|
| `User` | Participant identity and profile metadata | `displayName`, `createdAt` |
| `Room` | Room owner, status and timestamps | `ownerUserId` → User, `status`, `createdAt`, `updatedAt` |
| `RoomMember` | Membership and connection state | `roomId` → Room, `userId` → User, `membershipState`, `connectionState`, `joinedAt`, `leftAt` |
| `Draft` | Recording metadata and hosted file location | `ownerUserId` → User, `name`, `durationMs`, `effect`, `fileLocation`, `createdAt` |
| `Spin` | Room, status, start/completion time and winner | `roomId` → Room, `status`, `startedAt`, `completedAt`, `winnerUserId` → User (nullable), `startedByUserId` → User (nullable), `abortReason` (nullable) |
| `SpinParticipant` | Eligibility, elimination order/time and final status | `spinId` → Spin, `userId` → User, `status`, `eliminationOrder` (nullable), `eliminatedAt` (nullable), `eliminationReason` (`TIMER` \| `LEFT`, nullable) |
| `SpinEvent` | Auditable event or final outcome record | `spinId` → Spin, `sequenceNumber`, `eventType`, `payload`, `createdAt` |
| `RoomDraftShare` | Records that a Draft was shared into a Room, and by whom | `roomId` → Room, `draftId` → Draft, `sharedByUserId` → User, `sharedAt` |

**Why separate collections rather than embedding.** MongoDB would allow embedding members and spin
participants inside the room document, but spin participants are written concurrently on every
elimination tick and the event log grows without bound — both are poor fits for a single mutable
parent document, and a 16MB document ceiling is a real constraint on an unbounded event log. Separate
collections also let the uniqueness constraints in §3.3 be enforced by indexes.

`RoomDraftShare` exists because `draft_shared` is a room-scoped event while a Draft is owned by a
user. This is an implementation detail of the assessment's "Share a selected Draft with the room"
requirement, not an added requirement.

`SpinEvent.eventType` covers the three mandatory broadcast events (`spin_started`, `user_eliminated`,
`winner_announced`) plus `spin_aborted`, which is **persisted only and never broadcast**. Without it an
aborted spin's log would simply stop with no terminal record, weakening both the audit trail and
restart recovery.

### 3.2 Relationships

Relationships are ObjectId references (`ref`), populated on demand:

```
User 1───∞ Room            (owner)
User 1───∞ RoomMember      Room 1───∞ RoomMember
User 1───∞ Draft
Room 1───∞ Spin            Spin 1───∞ SpinParticipant
User 1───∞ SpinParticipant
Spin 1───∞ SpinEvent
Spin ∞───1 User            (winner, nullable until COMPLETED)
Room ∞───∞ Draft           (via RoomDraftShare)
```

MongoDB does not enforce referential integrity, so reference validity is maintained in the service
layer — a deliberate trade-off of the engine choice, noted in §9.

### 3.3 Constraints and invariants

Enforced by the database wherever MongoDB allows, so correctness does not depend solely on application
logic. MongoDB supports unique and **partial** indexes, which covers the two conditional invariants:

| Invariant | Enforcement |
|---|---|
| Only one active spin per room (C1) | Unique **partial** index on `Spin{ roomId }` with `partialFilterExpression: { status: { $in: ['WAITING', 'RUNNING'] } }` |
| A user holds at most one active membership per room | Unique **partial** index on `RoomMember{ roomId, userId }` filtered to `membershipState: 'JOINED'` |
| A user appears at most once per spin | Unique index on `SpinParticipant{ spinId, userId }` |
| Elimination order is unique within a spin | Unique partial index on `SpinParticipant{ spinId, eliminationOrder }` with `partialFilterExpression: { eliminationOrder: { $type: 'number' } }` |
| Spin event order is unique within a spin | Unique index on `SpinEvent{ spinId, sequenceNumber }` |
| Winner is set only on a COMPLETED spin | Mongoose schema validator (MongoDB has no check constraints; a JSON Schema validator on the collection is the alternative) |
| Eligible count within bounds (C1: min 3, max 20) | Validated at spin start in the service layer — a cross-document count is not expressible as an index |

The single-active-spin partial index is the load-bearing one: it makes "only one active spin may exist
in a room" a database guarantee, so the duplicate-start edge case (C4) cannot be lost to a race between
two concurrent requests.

The elimination-order filter must be `$type: 'number'` rather than `$exists: true`. Verified against
MongoDB 7: `$exists` also matches an explicit `null`, so an `$exists` filter would index every
not-yet-eliminated participant under a null key and permit only **one** of them per spin — breaking
every spin at the second participant. `$type: 'number'` indexes only real elimination orders.

### 3.4 Indexes and the queries they serve

| Index | Query it serves |
|---|---|
| `RoomMember{ roomId: 1 }` | Participant list for room state and broadcast fan-out — the hottest read |
| `Spin{ roomId: 1, status: 1 }` | Active-spin lookup on every start request and room-state fetch |
| `SpinParticipant{ spinId: 1, status: 1 }` | Selecting the next active participant each elimination tick |
| `SpinEvent{ spinId: 1, sequenceNumber: 1 }` | Ordered event replay for Get Spin Result and reconnect recovery |
| `Draft{ ownerUserId: 1, createdAt: -1 }` | Draft listing for a user, newest first |
| `RoomMember{ roomId: 1, membershipState: 1 }` | Active-participant filter for the room snapshot |
| `SpinParticipant{ spinId: 1, userId: 1 }` | Participant lookup during elimination and winner selection |
| `RoomDraftShare{ roomId: 1, sharedAt: -1 }` | Shared-draft list for a room snapshot, newest first |

---

## 4. REST API structure

All eight D1 endpoints, documented in OpenAPI/Swagger under `/docs/api`.

| Method | Path | D1 requirement | Purpose |
|---|---|---|---|
| `POST` | `/rooms` | Create Room | Create a room; caller becomes owner and first member (201) |
| `POST` | `/rooms/{roomId}/join` | Join Room | Add caller as a member; 201 created / 200 already a member; triggers `user_joined` |
| `POST` | `/rooms/{roomId}/leave` | Leave Room | Remove caller's membership (200); triggers `user_left` |
| `GET` | `/rooms/{roomId}` | Get Room State | Authoritative room snapshot: room, participants, shared drafts, active spin. Requires active membership |
| `POST` | `/rooms/{roomId}/drafts` | Share Draft | Share a selected Draft into the room; 201 shared / 200 already shared; triggers `draft_shared` |
| `POST` | `/users` | *(supporting)* | Creates a user identity so the endpoints above have a caller |
| `POST`/`GET` | `/drafts` | *(supporting)* | Registers and lists draft metadata, so a draft exists to share |
| `POST` | `/rooms/{roomId}/spins` | Start Spin | Owner/admin starts a spin; triggers `spin_started` |
| `GET` | `/spins/{spinId}` | Get Spin State or Result | Live spin state or final result with the persisted event sequence |
| `GET` | `/health`, `/ready` | Health / readiness endpoint | Liveness and dependency readiness for deployment health checks |

### 4.1 Conventions

- **Resource nesting** mirrors ownership: spins and draft shares are subordinate to a room.
- **Consistent error envelope** on every failure: a stable machine-readable `code`, a human-readable
  `message`, and field-level details for validation errors. Distinct codes per invalid operation
  (room not found, not a member, not the draft owner, room closed, spin already active, too few
  players, too many players) so the client can present the right state — this is what B1's
  "validation and invalid-operation handling" and D3's "validation, error handling" are scored on.
- **Idempotency** on the retry-prone mutations — Join Room, Share Draft and Start Spin — so a client
  retry or a duplicate tap cannot create a second spin or a duplicate membership (D3 idempotency;
  C4 duplicate start requests). Join and Share return **201** when the request created something and
  **200** when the caller's desired state already held; neither is an error. "Already a member" is
  therefore a success, not a distinct error code, and the database's unique partial indexes remain the
  backstop that makes this safe under concurrency.
- **State-changing responses return the new authoritative state**, so a client is never left guessing
  between the response and the broadcast that follows.

### 4.2 Caller identity (assessment simplification)

The assessment defines no authentication requirement and no scoring item covers it, so none is built.
Instead the caller states its identity with an **`X-User-Id` header** holding an existing User id; a
middleware verifies the user exists and rejects a missing (401), malformed (400) or unknown (401) id.

**This is a demo identity mechanism, not authentication.** There is no credential, signature or
session, so any client may claim any identity. It is isolated in `middleware/currentUser.ts` precisely
so it can be replaced by a real authentication step without touching any service: everything
downstream reads the caller from `req.currentUserId`.

`POST /users` and `POST /drafts` exist only to make the assessment's required endpoints usable — a
room needs callers, and Share Draft needs a draft that already exists.

### 4.3 REST / WebSocket division of labour

REST performs the mutation and returns the caller's result; the WebSocket layer informs *everyone
else* in the room. The two never disagree because the broadcast is emitted from the service layer
after persistence, not from the controller.

---

## 5. WebSocket events

Transport: WebSocket / Socket.IO. All seven events below are mandatory per B2. Sockets join a
server-side room channel keyed by `roomId`; every broadcast is scoped to that channel.

### 5.1 Server → client events

| Event | Assessment-required behavior | Proposed payload |
|---|---|---|
| `user_joined` | Broadcast updated participant information | joining user, updated participant list |
| `user_left` | Broadcast departure and clean presence after leave/disconnect | departing user, reason (leave / disconnect), updated participant list |
| `draft_shared` | Notify members that a Draft has been shared | draft metadata (name, duration, effect, file location), sharing user |
| `spin_started` | Publish active spin, eligible players and initial sequence state | spin id, status `RUNNING`, eligible player list, initial sequence state |
| `user_eliminated` | Publish each elimination with updated remaining players | spin id, eliminated user, elimination order, remaining player list, sequence number |
| `winner_announced` | Publish final winner and completed spin state | spin id, winner, status `COMPLETED`, final sequence number |
| `room_state` | Return latest state after connection or reconnection | full snapshot: room, participants, shared drafts, active spin with its current participant statuses and last sequence number |

### 5.2 Connection lifecycle

```
connect
   │
   ├─ identify socket → user, attach to room channel
   ├─ emit room_state  (full authoritative snapshot)
   └─ stream incremental events from that snapshot forward

disconnect
   │
   ├─ mark RoomMember connection state
   ├─ broadcast user_left (reason: disconnect)
   └─ apply the spin-participation rule if a spin is RUNNING

reconnect
   │
   ├─ re-attach to room channel
   └─ emit room_state  (client discards local state and adopts the snapshot)
```

**State synchronization rule (B2, 5 pts).** `room_state` carries the last emitted sequence number.
Incremental spin events carry monotonically increasing sequence numbers. A client applies an
incremental event only if its sequence number is exactly one past what it holds; otherwise it
requests a fresh snapshot. This makes duplicate events harmless and missed events detectable — the
same mechanism covers the C4 "duplicate events" case.

---

## 6. Room state model

### 6.1 Room status

The assessment defines a status field on Room but does not enumerate its values. Proposed minimal set,
sufficient for the required flows:

```
ACTIVE   — room exists and accepts joins, leaves, draft shares and spin starts
CLOSED   — room no longer accepts operations
```

**Phase 3 limitation — the owner leaving.** The owner may leave; the room stays `ACTIVE` and
ownership does **not** transfer to another member. Nothing in the assessment specifies an
owner-departure rule, and the only owner-restricted operation (Start Spin) does not exist until
Phase 4 — so no behaviour is invented here. The related spin-time case (TASKS SP-14, admin disconnect
during a running spin) is decided in Phase 4 where it is scored.

### 6.2 Member connection state

`RoomMember` is required to hold "membership and connection state". These are two separate axes, and
keeping them separate is what makes reconnect work:

| Axis | Values | Meaning |
|---|---|---|
| Membership | `JOINED`, `LEFT` | Did the user deliberately join / leave the room |
| Connection | `CONNECTED`, `DISCONNECTED` | Is a live socket currently attached |

A transport drop sets connection state to `DISCONNECTED` while membership stays `JOINED`, which is
precisely what allows a reconnecting client to be restored to the room and to its in-progress spin
participation. An explicit Leave sets membership to `LEFT` and is not recoverable by reconnecting.

### 6.3 Authoritative room snapshot

The snapshot returned by both `GET /rooms/{roomId}` and the `room_state` event:

```
Room
 ├─ id, status, owner, timestamps
 ├─ participants[]      user, membership state, connection state
 ├─ sharedDrafts[]      draft metadata + who shared it
 └─ activeSpin | null
       ├─ spin id, status, startedAt
       ├─ participants[]  user, status, eliminationOrder
       ├─ remainingPlayers[]
       ├─ winner | null
       └─ lastSequenceNumber
```

---

## 7. Spin state machine

### 7.1 States

The assessment specifies: `WAITING -> RUNNING -> COMPLETED`, with a branch `-> ABORTED`.

```
                 start (validated)
    ┌─────────┐ ─────────────────► ┌─────────┐
    │ WAITING │                    │ RUNNING │
    └─────────┘                    └────┬────┘
         │                              │
         │ abort                        │ one participant remains
         │                              ▼
         │                         ┌───────────┐
         │                         │ COMPLETED │  (terminal)
         │                         └───────────┘
         │                              
         │         abort condition      
         └──────────────┬───────────────┘
                        ▼
                  ┌─────────┐
                  │ ABORTED │  (terminal)
                  └─────────┘
```

### 7.2 Transition table

| From | To | Trigger | Guards (from C1) |
|---|---|---|---|
| — | `WAITING` | Spin created | No existing active spin in the room |
| `WAITING` | `RUNNING` | Owner/admin starts the spin manually | Caller is room owner/admin; eligible count ≥ 3 and ≤ 20; no other active spin |
| `RUNNING` | `RUNNING` | Elimination tick every 5 seconds | More than one active participant remains |
| `RUNNING` | `COMPLETED` | Exactly one active participant remains | Winner recorded and persisted; `winner_announced` emitted once |
| `WAITING` | `ABORTED` | Abort condition before start | — |
| `RUNNING` | `ABORTED` | Abort condition during the spin | Documented rule for participant shortfall / room closure |

`COMPLETED` and `ABORTED` are terminal. Every other transition is rejected — this is what C3's
"spin lifecycle and valid transitions" scores.

### 7.3 Participant status

| Status | Meaning |
|---|---|
| `ELIGIBLE` | Included in the eligible set snapshotted at spin start |
| `ACTIVE` | Still in the running, not yet eliminated |
| `ELIMINATED` | Eliminated, with `elimination_order` and `eliminated_at` recorded |
| `WINNER` | The single last-remaining participant |

Status transitions are one-way: `ELIGIBLE → ACTIVE → (ELIMINATED | WINNER)`.

### 7.3.1 Why an elimination records its reason

`eliminationReason` distinguishes the two ways a participant can be removed:

| Reason | Cause | Consumes a scheduled tick? |
|---|---|---|
| `TIMER` | A scheduled 5-second elimination | **Yes** |
| `LEFT` | The user explicitly left the room mid-spin | **No** |

This is not bookkeeping — it is load-bearing for restart recovery. Recovery works out how
many scheduled eliminations are still owed by subtracting those already applied from those due by
elapsed time. If a `LEFT` elimination were counted in that subtraction, recovery would believe a
scheduled tick had already run and would silently skip one, ending the spin early. Only `TIMER`
eliminations are counted against the schedule.

A **disconnect is not a departure**: it changes presence only, leaves membership `JOINED`, and has no
effect on spin participation. That is what allows a reconnecting client to resume.

### 7.3.2 Restart recovery

Recovery derives outstanding work from persisted state, never from elapsed time alone:

```
activeCount          = count(participants, ACTIVE)
timerEliminatedCount = count(participants, ELIMINATED AND reason == 'TIMER')

activeCount == 0 -> ABORT (NO_PARTICIPANTS_REMAINING)
activeCount == 1 -> COMPLETE with that participant as winner
otherwise:
  due     = floor((now - startedAt) / interval)
  pending = clamp(due - timerEliminatedCount, 0, activeCount - 1)
```

The clamp means recovery can never run past a single winner however long the process was down, and
every step re-reads live state so an already-eliminated participant is never eliminated twice.
Recovery is therefore **safe to run repeatedly**. Orphan `WAITING` spins — created by a start that
crashed before it finished — are aborted so they cannot occupy the room's single active-spin slot
forever.

### 7.3.3 Persistence and broadcast ordering

The order is always: apply the authoritative mutation → persist the `SpinEvent` → broadcast. A state
that was not persisted is never announced. If a broadcast fails the database remains correct and
clients repair themselves from `room_state`.

Because transactions are deliberately not used, two windows are accepted and documented:

- A crash between the mutation and its event leaves a **gap in the event log**. The mutation is
  authoritative; recovery continues correctly and clients resynchronize from the snapshot.
- Completion persists the spin document immediately before appending `winner_announced`, so a reader
  can briefly observe `COMPLETED` before its terminal event exists.

Sequence numbers and elimination orders are therefore **unique, monotonically increasing and
atomically assigned — but not gapless**. Nothing depends on contiguity: ordering uses sorting, clients
compare against the snapshot's `lastSequenceNumber`, and recovery derives work from participant state.

The client synchronization rule: apply an incremental event when it follows the sequence already held;
on a gap, adopt a fresh `room_state`; discard stale events. `room_state` always wins over the stream.

### 7.4 Elimination sequence and event order

Per C2, events must be emitted in the correct order. For an eligible set of *n* participants
(3 ≤ n ≤ 20), a complete spin produces exactly one `spin_started`, exactly *n − 1* `user_eliminated`
events at 5-second intervals, and exactly one `winner_announced`:

```
t=0s    spin_started        seq 1     n active
t=5s    user_eliminated     seq 2     n-1 active
t=10s   user_eliminated     seq 3     n-2 active
  ...
t=5(n-1)s  user_eliminated  seq n     1 active
           winner_announced seq n+1   winner = the last active participant
```

Each event is persisted as a `SpinEvent` with its sequence number **before** being broadcast, so the
persisted log, the broadcast stream and the Get Spin Result response are the same sequence.

### 7.5 Where the edge cases attach

The C4 edge cases named in the assessment map onto specific points in this machine — the mapping
below is the design hook; each chosen outcome is to be decided, implemented and documented per
TASKS.md items SP-8 to SP-17:

| Edge case | Attachment point |
|---|---|
| Duplicate start requests | `WAITING → RUNNING` guard + per-room serialization + start idempotency |
| Simultaneous joins | Room membership writes + the eligible-set snapshot taken at start |
| User departure during a spin | Participant status update while `RUNNING`; winner rule must stay valid |
| Reconnect during a spin | `room_state` snapshot with `lastSequenceNumber` |
| Admin disconnect | Whether `RUNNING` continues when the owner's socket drops |
| Insufficient players | Start guard (< 3) and the mid-spin shortfall path |
| Last players leaving | `RUNNING → ABORTED` vs `RUNNING → COMPLETED` decision |
| Duplicate events | Sequence-number dedup rule in §5.2 |
| Delayed timers | Tick scheduler drift handling; order preserved regardless of wall-clock jitter |
| Server restart | Recovery from persisted `Spin` + `SpinEvent` state on boot |

---

## 8. Deployment architecture

Provider: **Google Cloud Run** in `asia-south1`, with **MongoDB Atlas** as the managed database.

```
push to main
      │
      ▼
GitHub Actions
  verify (ci.yml)  typecheck → lint → unit → integration (real MongoDB) → build
      │  fail = stop, nothing is deployed
      ▼
  deploy (deploy.yml)
      ├─ record the currently serving revision        ← rollback target
      ├─ build image, tag :<commit-sha> and :latest
      ├─ push to Artifact Registry (asia-south1)
      ├─ gcloud run deploy
      ├─ smoke test: /health, /ready, real WebSocket upgrade
      └─ smoke fails → shift traffic back automatically
      ▼
Cloud Run service (roxstar-backend)
      ├─ min-instances=1, max-instances=1   ← in-process state, see below
      ├─ session affinity, request timeout 3600s (WebSockets)
      ├─ PORT injected (8080); MONGODB_URI from Secret Manager
      └─ startup probe /ready · liveness probe /health
      │
      ▼ TLS (mongodb+srv)
MongoDB Atlas (managed, M0)
```

**Exactly one instance is a correctness requirement, not tuning.** Presence tracking
(`presenceRegistry`), spin timers (`spinScheduler`) and the per-room mutex (`roomMutex`) are all
in-process. A second instance would split that state — two schedulers could drive one spin. The
database invariants in §3.3 would still hold, but the in-memory coordination would need replacing
with a shared adapter, which is out of scope.

Authentication to GCP uses **Workload Identity Federation**: GitHub mints a short-lived OIDC token
per run, scoped by attribute condition to this repository. No long-lived service-account key exists.

Rollback is a traffic switch between immutable revisions, not a rebuild — see
[docs/deployment.md](docs/deployment.md) for the runbook.

Environment-based configuration and documented secrets handling are required by Section E; no
credentials are committed to the repository, and an example environment file with placeholder values
documents the required variables. A local-only backend is explicitly not accepted as the final
submission, so the hosted endpoint is a submission gate rather than an optional extra.

---

## 9. Decisions

### 9.1 Settled

| Decision | Choice | Justification |
|---|---|---|
| Backend runtime and language | Node.js 22 + TypeScript (ESM, `NodeNext`) | Node.js is mandated by the assessment; TypeScript makes the spin state machine and event payloads compile-time checkable, which is where the reasoning marks are |
| HTTP framework | Express 5 | Smallest framework that covers the eight required endpoints; native async error propagation removes per-route try/catch |
| Realtime | Socket.IO | Named in the assessment; built-in room channels match the per-room broadcast model in §5, and its reconnect support backs B2's reconnect requirement |
| Database engine | **MongoDB** with Mongoose | Allowed by the assessment. The spin event log is append-only documents of varying shape, which suits a document store; Mongoose gives schema validation and typed models over it. Unique **partial** indexes (§3.3) enforce the single-active-spin invariant, which was the main correctness risk in choosing a document database |
| Validation | Zod | One schema library for both environment configuration and request bodies |
| Logging | Pino | Structured JSON logs, so room and spin correlation IDs are queryable (D3) |
| Testing | Vitest + Supertest | Native TypeScript/ESM support without extra transform configuration |

**Trade-off accepted with MongoDB:** no foreign keys and no cross-document check constraints, so
referential integrity and the eligible-count bounds are enforced in the service layer rather than by
the engine. Multi-document atomicity, where required, needs an explicit transaction on a replica set.

### 9.2 Settled in Phase 5

| Decision | Choice | Justification |
|---|---|---|
| Cloud provider | **Google Cloud Run** (`asia-south1`) | Native WebSocket support, runs the existing Dockerfile unchanged, managed TLS, immutable revisions so rollback is a traffic switch, and Secret Manager integration. AWS App Runner lacks dependable WebSocket support; ECS+ALB and Kubernetes add infrastructure for no rubric gain |
| Production database | **MongoDB Atlas** (managed) | The development compose MongoDB is not suitable for production; Atlas gives managed backups and TLS with no new infrastructure |
| CI/CD | **GitHub Actions** | Already where the repository lives; Workload Identity Federation removes long-lived cloud keys |

### 9.3 Still open

| Decision | Options allowed by the assessment | Note |
|---|---|---|
| Voice effect | Echo, Reverb or Pitch Shift | At least one; must sit in the Oboe/native path where practical |
| Abort conditions | Not enumerated by the assessment | The `ABORTED` branch exists in C1; which conditions trigger it is a documented design decision |
| Room status values | Not enumerated | Room "status" is required by the Room entity; the value set is a design decision |
| Virtual points award | Optional | "Virtual points may be awarded to the winner" — wallet/payment logic explicitly not required |
