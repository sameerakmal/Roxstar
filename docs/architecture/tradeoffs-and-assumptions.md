# Architectural Assumptions, Trade-offs & Limitations

This document captures the rationale, explicit assumptions, engineering trade-offs, and runtime limitations of the Roxstar application.

---

## 1. Local Audio Storage vs Cloud Audio Upload

### Decision
Audio files remain strictly on the local Android device (`context.filesDir/drafts/<id>.wav`). When a voice draft is "shared" to a room, only draft metadata (title, duration, effect used, and file location identifier) is registered with the backend and broadcast over WebSockets.

### Rationale & Trade-offs
- **Advantages**:
  - **Zero Bandwidth & Latency**: Recording and local playback require no network roundtrips. Works 100% offline.
  - **Real-time Safety**: File writing is contained entirely on the local device, avoiding complex network buffer management inside the native audio engine.
  - **Scope Alignment**: Live audio streaming, WebRTC, and media storage services (e.g., AWS S3 / Azure Blob Storage) were explicitly out of scope per the technical assessment.
- **Trade-off**: Other room participants receive notification of the draft's existence and metadata, but do not stream the raw audio bits across devices.

---

## 2. Identifier Strategy: Client UUIDs vs Server ObjectIds

### Decision
- **Local Drafts**: Use standard UUIDv4 strings generated client-side (`java.util.UUID.randomUUID().toString()`).
- **Backend Entities**: Users, Rooms, Memberships, Shared Draft records, Spins, and Spin Events use 24-hex MongoDB `ObjectId`s.

### Rationale & Trade-offs
- Generating UUIDs locally allows the Android client to create, save, rename, and manage voice drafts entirely offline without a server pre-allocation step.
- When shared to a room, the backend generates an `ObjectId` for the `SharedDraft` record while retaining the client's local draft metadata.

---

## 3. REST Membership Before Socket.IO Room Join

### Decision
A client must join a room via the REST API (`POST /rooms/:roomId/join`) before their Socket.IO connection is allowed to subscribe to the room's event channel (`join_room`).

### Rationale & Trade-offs
- **Single Source of Truth**: Room membership is authoritative in MongoDB, created through validated domain logic in Express.
- **No Split State**: Socket connections never mutate membership; they only register presence.
- If a client attempts `socket.emit("join_room", { roomId })` without an active database membership, the server rejects the request with `{ ok: false, code: "NOT_A_MEMBER" }`.

---

## 4. Presence vs Room Membership Separation

### Decision
Socket disconnects modify in-memory presence only; they do **not** revoke database room membership and do **not** eliminate players from an active spin.

### Rationale & Trade-offs
- Mobile network transitions frequently cause brief socket drops. If transport disconnects triggered game elimination or room departure:
  - Users would lose games unfairly due to transient cellular handover.
  - State would flutter continuously.
- **Resolution**: Explicit departure (`POST /rooms/:roomId/leave`) is required to exit a room and immediately forfeit an active spin. Disconnecting merely marks `connectionState = 'DISCONNECTED'`, allowing seamless reconnection via `room_state`.

---

## 5. Assessment Identity Model vs Cryptographic Auth

### Decision
Identity is passed via the `x-user-id` header in REST and `auth.userId` in Socket.IO. The server trusts the provided user ID without signature verification or password exchange.

### Rationale & Trade-offs
- The assessment specification focuses on audio DSP, real-time synchronization, and concurrency rather than auth token management.
- The `currentUser` middleware validates that the user exists in MongoDB and injects `req.currentUserId`.
- In a production environment, this middleware would be replaced with JWT or OAuth bearer token verification without altering downstream controllers or services.

---

## 6. Single-Replica Cloud Deployment Pinning

### Decision
The backend container is pinned to exactly one replica (`minReplicas: 1, maxReplicas: 1`, `activeRevisionsMode: Single`) in Azure Container Apps (`infrastructure/containerapp.yaml`).

### Rationale & Trade-offs
- In-process components (`PresenceRegistry`, `SpinScheduler`, and `RoomMutex`) maintain state in Node.js memory:
  - `RoomMutex` serializes concurrent mutations for a room in memory.
  - `SpinScheduler` holds active `setTimeout` timers for elimination ticks.
- While database constraints (partial unique indexes, CAS updates) prevent data corruption across multiple instances, multiple replicas would split socket presence and spin timer execution.
- Running single-replica eliminates the need for external distributed locks (e.g. Redis Redlock) and distributed socket adapters (Redis Streams/PubSub), keeping the architecture robust and self-contained.

---

## 7. Standalone MongoDB Without Multi-Document Transactions

### Decision
The backend operates against standalone MongoDB without requiring replica-set multi-document transactions (`session.withTransaction()`).

### Rationale & Trade-offs
- Relies on single-document atomic operators (`$set`, `$inc`, `$push`), compound unique indexes, and conditional updates (Compare-And-Swap) to enforce consistency:
  - Unique partial index prevents duplicate active spins.
  - Atomic CAS `completeSpin` ensures exactly one winner is declared.
- **Trade-off**: Event log writes and state transitions are ordered monotonically. In the rare event of a process crash between status update and event append, client reconnection repairs state from the authoritative `room_state` snapshot.

---

## 8. Real-Time Audio Callback Safety

### Decision
All dynamic memory allocations, file I/O, locking, JNI operations, and logging are strictly excluded from the Oboe `onAudioReady` callback.

### Rationale & Trade-offs
- Ring buffer capacity is pre-allocated at `prepare()` time:
  $$\text{Capacity} = (\text{sampleRate} \times 2.0\text{s}) + 1024\text{ frames}$$
- Disk writes are offloaded to an asynchronous background worker thread (`writerLoop`).
- **Trade-off**: Under extreme CPU starvation where the writer thread is blocked for > 2 seconds, the ring buffer will overrun and drop frames (`overrunFrames` counter incremented) rather than blocking or glitching the OS audio pipeline.

---

## 9. Verification & Runtime Environment Limitations

### Verified Automated Testing
- **Host Unit & Integration Tests**: All unit tests (Android JVM, native C++ mini-test, backend Vitest) and backend integration tests were fully executed and passed on the development host.
- **Native Android Build & Lint**: Android debug compilation (`assembleDebug`), CMake cross-compilation for 3 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`), and Android lint (`lintDebug`) were fully executed and passed.

### Environment Limitations
- **Physical Device / Emulator Runtime**: No physical Android hardware or running Android emulator was accessible in this headless agent execution environment. Real-time microphone audio input quality and live speaker output could not be acoustically sampled on physical hardware; verification was conducted via automated DSP signal tests and JVM contract tests.
