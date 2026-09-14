# TASKS.md — Roxstar Voice Draft, Room & Spin Wheel System

Implementation checklist derived from the Roxstar Candidate Technical Assessment (200 points).

Every task below traces to a scoring item in the assessment. Nothing here is invented beyond the
document; where the assessment says "suggested" or "at least one", that is marked explicitly.

**Status markers** — an ID prefixed `[x]` is complete, `[~]` is partially complete, and an unmarked ID
is not started. See the [Phase log](#phase-log) at the end for what each phase delivered.

**Point accounting**

| Assessment section | Covered by checklist groups | Max |
|---|---|---|
| A. Android Audio Studio using Oboe | Android Audio (A1, A3), Drafts (A2) | 40 |
| B. Room and Real-Time Communication | Rooms (B1), WebSockets (B2) | 40 |
| C. Spin Wheel Logic and Reasoning | Spin Engine (C2, C3, C4) | 50 |
| D. Backend and Database Engineering | Backend APIs + Database (D2), Testing (D3 partial) | 30 |
| E. Cloud and DevOps | Docker, CI/CD, Cloud | 20 |
| F. Documentation and Communication | Documentation | 20 |
| Demo checklist (evidence gate) | Demo | — (gates the above) |
| **Total** | | **200** |

Note: the Demo group carries no points of its own. Section 9 of the assessment is a demonstration
checklist and the C4 quality rule states edge cases count only when implemented or convincingly
tested and explainable — so the Demo group is what converts implemented work into awarded points.

---

## 1. Android Audio

Assessment sections A1 (10 pts) and A3 (20 pts). Pipeline mandated by the assessment:
`Microphone -> Oboe input stream -> Effect processing -> Encoding / file writer -> Local Draft storage -> Playback`

### A1. Voice recording — 10 points

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| AA-1 | Record microphone input using Oboe | Integrate Oboe via NDK/CMake; open an input stream, run a recording callback, manage full stream lifecycle (open → start → stop → close) including error/disconnect callbacks | Demo: record a clip through the Oboe path (Demo checklist item 1). Log or UI showing stream open/close and sample rate/channel config | 5 (Oboe integration and stream lifecycle) |
| AA-2 | Save a valid local audio file with correct playback quality | Write captured PCM to a valid local file via the encoder/file-writer stage; implement playback of the saved file | Demo: play back the recorded clip and confirm it is audible and not corrupted. Instrumented or manual check that file duration matches recording duration | 3 (Valid recording and playback quality) |
| AA-3 | Support Start, Stop and Cancel | Implement three distinct controls: Start begins capture, Stop finalizes and saves, Cancel aborts and discards the partial file | Demo: exercise all three; show Cancel leaves no Draft behind | counted within AA-1/AA-2 (A1 lists this as a requirement, not a separate scoring line) |
| AA-4 | Handle microphone permission and expected failures | Runtime RECORD_AUDIO permission request + denial path; handle expected failures (permission denied, stream open failure, device disconnect, audio focus loss) with user-visible state rather than a crash | Demo checklist item 11: trigger one expected failure and explain the handling. Test: deny permission and show the handled state | 2 (Permission and failure handling) |

### A3. Voice effect — 20 points

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| AA-5 | Implement at least one effect: Echo, Reverb or Pitch Shift, in the Oboe/native audio path where practical | Choose one effect (assessment requires only one) and implement it in native code on the captured buffer path so the effect sits between the Oboe input stream and the file writer | Demo checklist item 2: apply the effect and play the result; A/B the dry vs. processed clip | 10 (Working effect implementation) |
| AA-6 | Sound audio processing and buffer design | Design the processing buffers explicitly: fixed frame sizes, no allocation/locking/logging inside the audio callback, documented delay-line or ring-buffer sizing for the chosen effect | Code walkthrough of the buffer design; document the buffer/latency choices in the audio-flow doc (feeds F: audio-flow diagram) | 5 (Audio processing and buffer design) |
| AA-7 | Stability across repeated operations and lifecycle changes | Make record/stop/record repeatable; handle Android lifecycle (background/foreground, rotation, app pause) and release streams deterministically; guard against double-start and double-stop | Test: repeat record→effect→save at least ~10 cycles plus a rotation and a background/foreground transition without crash, leak, or stuck stream. Show this in the demo | 5 (Stability across repeated operations and lifecycle changes) |

---

## 2. Drafts

Assessment section A2 (10 points).

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| DR-1 | Save recordings as Drafts | Persist the processed recording as a Draft with its metadata (name, creation time, duration) in local storage | Demo checklist item 3: save a Draft | 5 (Draft save/list/play/delete correctness) — shared across DR-1..DR-3 |
| DR-2 | List Drafts with name, creation time and duration | Build the Draft list screen showing all three fields per Draft | Demo: show the list with all three fields populated | (see DR-1) |
| DR-3 | Play and delete a Draft | Implement per-Draft playback and delete, deleting both the metadata record and the audio file | Demo checklist item 3: play a Draft, delete a Draft, confirm it disappears from the list and the file is gone | (see DR-1) |
| DR-4 | Android structure and separation of concerns | Layer the app: UI ← ViewModel/state ← repository ← native audio engine + storage; keep native audio behind a clear interface | Code walkthrough showing layers and the JNI boundary | 3 (Android structure and separation of concerns) |
| DR-5 | Usability and state presentation | Present recording/idle/playing/error states clearly, including empty-list and in-progress states | Demo: show idle, recording, playing and an error state in the UI | 2 (Usability and state presentation) |
| DR-6 | Share a selected Draft with the room | Wire the Draft list to the backend Share Draft API (see BA-5) so a selected Draft can be shared into the joined room | Demo checklist item 5: `draft_shared` observed on a second client after sharing | points scored under B1/B2 and D1; listed here as the Android-side task |

---

## 3. Rooms

Assessment section B1 (20 points) and the room half of D1.

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| RM-1 | Create Room, Join Room, Leave Room, Get Room Details and participant list, Share a selected Draft with the room — as REST APIs | Design and implement the five room endpoints with consistent resource naming, status codes, request/response shapes and error envelope | OpenAPI/Swagger document (also scored in F); integration tests per endpoint | 5 (REST API design) |
| RM-2 | Authoritative room state | The Node.js service owns room state: membership, owner, room status. Clients never assert state; every mutation goes through the server which then broadcasts. Serialize mutations per room | Test: concurrent join/leave against one room converge to one correct participant list. Demo: second client's view matches the server's `room_state` | 5 (Authoritative room state) |
| RM-3 | Validation and invalid-operation handling | Validate every request and reject invalid operations with distinct errors: join a non-existent room, join twice, leave a room you are not in, share a Draft you do not own, act on a room you are not a member of, malformed payloads | Unit + integration tests asserting status code and error code per invalid operation. Demo checklist item 11: trigger one expected failure and explain the handling | 5 (Validation and invalid-operation handling) |
| RM-4 | Room and membership data model | Model Room and RoomMember (see Database group) with owner, status, timestamps and per-member connection state | Schema/migration files and the database diagram (F deliverable) | 5 (Room and membership data model) |

---

## 4. WebSockets

Assessment section B2 (20 points). All seven events below are mandatory per the assessment.

### Mandatory events

| # | Event | Required behavior (verbatim intent from the assessment) | Implementation task | Test / demo evidence |
|---|---|---|---|---|
| WS-1 | `user_joined` | Broadcast updated participant information | Emit to the room on join, carrying the updated participant info | Two-client demo (Demo checklist item 5); integration test asserting receipt |
| WS-2 | `user_left` | Broadcast departure and clean presence after leave/disconnect | Emit on both explicit leave and transport disconnect; clean presence state in both paths | Demo: close the second client and observe `user_left`; test covering leave and abrupt disconnect |
| WS-3 | `draft_shared` | Notify members that a Draft has been shared | Emit to the room when a Draft is shared, carrying Draft metadata | Demo checklist item 5; integration test |
| WS-4 | `spin_started` | Publish active spin, eligible players and initial sequence state | Emit on successful spin start with spin id, eligible player list and initial sequence state | Demo checklist item 7; integration test asserting payload contents |
| WS-5 | `user_eliminated` | Publish each elimination with updated remaining players | Emit once per elimination with the eliminated user and the updated remaining list | Demo: eliminations visible every 5 seconds; test asserting one event per elimination with a shrinking remaining list |
| WS-6 | `winner_announced` | Publish final winner and completed spin state | Emit once when one participant remains, with the winner and the COMPLETED spin state | Demo: exactly one winner; test asserting exactly one `winner_announced` per spin |
| WS-7 | `room_state` | Return latest state after connection or reconnection | Emit a full authoritative snapshot on connect and on reconnect, including any in-progress spin | Demo checklist item 8: reconnect / state-recovery flow; test that a client reconnecting mid-spin receives correct current state |

### Scoring items

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| [~] WS-8 | Connection and disconnect management | Authenticate/identify the socket, map socket ↔ user ↔ room, join the socket room on connect, tear down cleanly on disconnect, handle duplicate connections for the same user | Test: connect, disconnect, verify presence cleanup and no orphaned socket-room membership | 5 (Connection and disconnect management) |
| WS-9 | Correct event handling and room broadcasting | Broadcast strictly to the room's members; correct payload schema per event; no cross-room leakage | Integration test with two rooms asserting events do not cross; demo with two clients | 5 (Correct event handling and room broadcasting) |
| WS-10 | Reconnect logic | Support client reconnect and re-attachment to the room, re-emitting `room_state`; define the disconnect grace behavior for room membership and spin eligibility (ties into SP-9) | Demo checklist item 8; test: drop and restore a connection mid-spin and confirm the client re-synchronizes | 5 (Reconnect logic) |
| WS-11 | State synchronization | Guarantee that the `room_state` snapshot and the incremental event stream cannot diverge — snapshot-then-stream ordering, event sequencing so a late/duplicate event is detectable | Test: a client that misses events mid-spin ends with the same final state as one that received all events | 5 (State synchronization) |

---

## 5. Spin Engine

Assessment section C (50 points). Core rules from C1; state machine `WAITING -> RUNNING -> COMPLETED`, with `-> ABORTED`.

### C2. Basic functionality — 20 points

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| SP-1 | Validate and start the wheel — min 3 / max 20 eligible users; started manually by admin or room owner; only one active spin per room | Implement start validation: eligible-count bounds, caller is admin/room owner, no active spin already present in the room | Tests: start with 2 (reject), 3 (accept), 21 (reject), non-owner caller (reject), start while a spin is RUNNING (reject). Demo checklist item 6: start with at least 3 users | 5 (Validate and start the wheel) |
| SP-2 | Generate and process eliminations — one active participant eliminated every 5 seconds after start | Implement the server-side 5-second elimination scheduler selecting one active participant per tick and updating status | Demo checklist item 7: eliminations visibly every 5 seconds. Test with a controllable clock asserting elimination cadence and one elimination per tick | 5 (Generate and process eliminations) |
| SP-3 | Select exactly one valid winner — the last remaining participant | Stop elimination when one active participant remains; record that participant as the winner; optionally award virtual points (assessment: virtual points only, no wallet/payment) | Test: run N spins and assert exactly one winner each, and that the winner was never eliminated. Demo checklist item 7 | 5 (Select exactly one valid winner) |
| SP-4 | Emit events in the correct order | Guarantee ordering: `spin_started` → `user_eliminated` × (n−1) → `winner_announced`, with persistence of the event sequence | Test asserting the exact recorded event order for a full spin; retrieve the persisted sequence via the Get Spin State/Result API | 5 (Emit events in the correct order) |

### C3. State management — 15 points

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| SP-5 | Room state consistency | Keep room state and spin state coherent: a room reflects its active spin, and room membership changes during a spin are reconciled against spin participation | Test: join/leave during a RUNNING spin leaves room state and spin state mutually consistent | 5 (Room state consistency) |
| SP-6 | Spin lifecycle and valid transitions | Implement the state machine explicitly; reject illegal transitions (COMPLETED → RUNNING, ABORTED → RUNNING, double COMPLETED); persist each transition | Unit tests over the transition table covering every legal and a representative set of illegal transitions | 5 (Spin lifecycle and valid transitions) |
| SP-7 | Participant eligibility and status tracking | Track per-participant eligibility and status (eligible / active / eliminated with order and time / winner); snapshot the eligible set at spin start | Test asserting elimination order and timestamps are recorded and that status transitions are one-way | 5 (Participant eligibility and status tracking) |

### C4. Edge-case reasoning — 15 points

Scoring is banded: 1–2 cases = 5, 3–4 cases = 10, **5 or more = 15**. The quality rule requires each
case to be implemented or convincingly tested *and* explainable. Target ≥5 implemented cases with a
test each and a documented chosen outcome. All candidates below are named in the assessment.

| # | Edge case (from the assessment's suggested list) | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| SP-8 | Duplicate start requests | Idempotency/locking on start so two concurrent or repeated start requests produce exactly one spin | Test firing concurrent start requests, asserting one spin and one `spin_started` | contributes to the 15-point band |
| SP-9 | User departure during a spin | Define and implement the outcome (e.g. treat as eliminated / mark inactive) and keep the winner rule valid | Test: a participant leaves mid-spin; assert spin still completes with exactly one winner. Demo checklist item 9 | contributes |
| SP-10 | Reconnect during a spin | Reconnecting client receives current spin state via `room_state` and resumes receiving elimination events | Demo checklist items 8 and 9; integration test | contributes |
| SP-11 | Insufficient players | Reject start below 3; define behavior if the active count falls below the minimum mid-spin (e.g. ABORTED or complete-with-winner) and implement it | Tests for both the start-time rejection and the mid-spin shortfall path | contributes |
| SP-12 | Last players leaving | Define and implement the outcome when all or all-but-one participants leave (transition to ABORTED or COMPLETED per documented rule) | Test draining participants mid-spin and asserting the documented terminal state | contributes |
| SP-13 | Duplicate events | Make event emission and handling idempotent/deduplicated so a repeated elimination or winner event cannot double-apply | Test replaying an event and asserting state is unchanged | contributes |
| SP-14 | Admin disconnect | Define and implement whether a RUNNING spin continues when the admin/owner disconnects | Test: disconnect the owner mid-spin and assert the documented outcome | contributes |
| SP-15 | Simultaneous joins | Serialize concurrent joins so the participant list and eligibility snapshot stay correct | Concurrency test with simultaneous joins | contributes |
| SP-16 | Delayed timers | Handle tick drift/late timers so elimination cadence and ordering stay correct rather than compounding | Test with an artificially delayed tick asserting no skipped or doubled elimination | contributes |
| SP-17 | Server restart | Define and implement recovery for a spin that was RUNNING at restart (resume from persisted state or transition to ABORTED) per a documented rule | Test: restart the process mid-spin and assert the documented recovery outcome | contributes |

---

## 6. Backend APIs

Assessment section D1 (required APIs) and D3 (backend quality, 15 points). The endpoint-design points
themselves are scored under B1 (RM-1).

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| BA-1 | Create Room | Implement the endpoint with owner assignment and initial room status | Integration test + OpenAPI entry | scored via RM-1 / D3 |
| BA-2 | Join Room | Implement join with membership creation and `user_joined` broadcast | Integration test asserting membership row and broadcast | scored via RM-1 / D3 |
| BA-3 | Leave Room | Implement leave with membership cleanup and `user_left` broadcast | Integration test covering leave and the disconnect path | scored via RM-1 / D3 |
| BA-4 | Get Room State | Implement the authoritative room snapshot including participants and any active spin | Test that the REST snapshot matches the `room_state` socket payload | scored via RM-1 / D3 |
| BA-5 | Share Draft | Implement sharing a Draft into a room (Draft metadata and hosted file location per the Draft entity) with `draft_shared` broadcast | Demo checklist item 5; integration test | scored via RM-1 / D3 |
| BA-6 | Start Spin | Implement the spin-start endpoint delegating to the Spin Engine validation (SP-1) | Tests from SP-1; demo checklist item 6 | scored via C2 / D3 |
| BA-7 | Get Spin State or Result | Implement retrieval of live spin state and final result including the persisted event sequence (user journey step 8) | Test: fetch mid-spin and post-spin; assert result matches broadcast events | scored via C2 / D3 |
| [x] BA-8 | Health / readiness endpoint | Implement health and readiness endpoints reporting process and dependency (database) status | Used by the cloud deployment health check (CL-3) and demo checklist item 10 | scored via D3 / E |
| [~] BA-9 | Validation, error handling and idempotency | Central request validation, a consistent error envelope, and idempotent handling for repeat/retry-prone operations (start spin, join, share) | Tests per invalid input and per duplicate request | 5 (Validation, error handling and idempotency) |
| [~] BA-10 | API/service organization and logging | Organize into routes → controllers → services → repositories with the Spin Engine as its own service; structured logging with request/room/spin correlation | Code walkthrough; log excerpt from a full spin showing correlated entries | 5 (API/service organization and logging) |

---

## 7. Database

Assessment section D2 (15 points). Entity list below is the assessment's suggested set. Engine choice
is free among PostgreSQL, MySQL or MongoDB — the assessment requires justifying schema, keys and indexes.

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| DB-1 | Entities: User, Room, RoomMember, Draft, Spin, SpinParticipant, SpinEvent/Result | Define each entity per its stated purpose: User (identity/profile metadata); Room (owner, status, timestamps); RoomMember (membership and connection state); Draft (recording metadata and hosted file location); Spin (room, status, start/completion time, winner); SpinParticipant (eligibility, elimination order/time, final status); SpinEvent/Result (auditable event or final outcome) | Schema/migration files committed; database diagram (F deliverable, submission checklist item) | 5 (Schema and keys) |
| DB-2 | Schema and keys | Choose and document primary keys and natural/unique keys (e.g. one active spin per room, one membership per user per room) | Migration files; written key justification in docs | (see DB-1) |
| DB-3 | Relationships, constraints and indexes | Foreign keys across Room→RoomMember, Room→Spin, Spin→SpinParticipant, Spin→SpinEvent, User→Draft; constraints enforcing the invariants (single active spin, unique membership, unique elimination order within a spin); indexes for the hot read paths (room state fetch, spin event replay, draft listing) | Written justification of each index against the query it serves; migration files | 5 (Relationships, constraints and indexes) |
| DB-4 | Queries and persistence correctness | Implement persistence for room state, spin lifecycle transitions, eliminations and the event sequence; ensure the spin result and event sequence are durably recorded (C1: persist the result) | Integration tests reading back a completed spin and its full event sequence; test that a mid-spin crash leaves recoverable state (ties to SP-17) | 5 (Queries and persistence correctness) |
| DB-5 | Migrations | Provide runnable schema migrations (submission package requires database schema/migrations) | Clean-database migration run documented in the README | part of DB-1/DB-4 and the submission checklist |

---

## 8. Testing

Assessment section D3 testing line (5 points) plus the evidence obligations across B, C and the demo checklist.

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| [~] TS-1 | Unit and integration testing | Unit tests for spin state machine transitions, elimination selection and validation rules; integration tests for the REST endpoints and the WebSocket event flows against a real database | Test suite runs in CI (CD-1) and locally per the README | 5 (Unit and integration testing) |
| TS-2 | Full-spin integration test | End-to-end test: create room → three clients join → start spin → assert `spin_started`, two `user_eliminated`, one `winner_announced` in order → assert persisted result | Test output shown in the demo (Demo checklist item 10) | supports C2 (SP-4) |
| TS-3 | Edge-case test coverage | One test per implemented edge case in SP-8..SP-17, each asserting the documented chosen outcome (required by the C4 quality rule) | Named tests mapping 1:1 to the documented edge-case list | supports C4 band |
| TS-4 | Reconnect / state-sync test | Test that a reconnecting client receives `room_state` and converges with clients that stayed connected | Demo checklist item 8 | supports B2 (WS-10, WS-11) |
| TS-5 | Automated test results included | Capture and include the test run output as submission evidence | Submission checklist: "Automated test results included" | part of F / submission package |

---

## 9. Docker

Assessment section E (Docker packaging, 5 points).

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| [~] DK-1 | Package the Node.js backend using Docker | Write the backend Dockerfile: pinned base image, dependency install, production build, non-root user, exposed port, and a container-level health check aligned with BA-8 | `docker build` and `docker run` reproducibly start the backend; show the image running | 5 (Docker packaging) |
| [~] DK-2 | Local development composition | Provide local orchestration for backend + database so the README clean-start instructions work end to end | README clean-start walkthrough succeeds on a fresh machine (F: README, 5 pts) | supports DK-1 and F |

Assessment note: Docker may be used locally during development, but a local-only backend is **not**
accepted as the final submission — the cloud deployment (CL-1) is mandatory.

---

## 10. CI/CD

Assessment section E (CI/CD automation, 5 points).

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| CD-1 | Pipeline installs dependencies, runs tests, builds and deploys | Implement the pipeline with those four stages in order, failing the build on test failure and deploying only from a successful build | Pipeline run history showing the four stages; a green run and a deliberately failing run. Demo checklist item 10: CI/CD evidence | 5 (CI/CD automation) |
| CD-2 | Pipeline builds and publishes the Docker image | Build the image in CI and push it to the chosen cloud's registry, tagged per commit for traceability | Registry showing commit-tagged images | supports CD-1 and CL-1 |
| CD-3 | Secrets in the pipeline | Store deployment credentials as CI secrets, never in the repository; document the handling | Written secrets-handling section (E requirement); no credentials in git history | supports EN-1 |

---

## 11. Cloud

Assessment section E (working cloud deployment 5 pts, environment configuration and release safety 5 pts).

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| CL-1 | Deploy to AWS, GCP or Azure — one provider | Deploy the containerized backend and its managed database to the chosen provider; ensure the WebSocket transport works through the chosen ingress | Live hosted endpoint reachable during the demo (Demo checklist item 10); submission field "Cloud provider and endpoint" | 5 (Working cloud deployment) |
| CL-2 | Working hosted endpoint or clear deployment evidence | Publish the endpoint URL and capture deployment evidence (console/CLI output, service status) | Hosted endpoint demonstrated live plus captured evidence in the repo | (see CL-1) |
| CL-3 | Health verification | Document and demonstrate how deployment health is verified using the health/readiness endpoint (BA-8) | Health check output against the hosted endpoint | part of EN-1 band |
| CL-4 | Rollback approach | Document the rollback path (redeploy prior image tag / revision rollback) and verify it works | Documented rollback procedure plus evidence of an executed or rehearsed rollback | part of EN-1 band |
| [~] EN-1 | Environment-based configuration and documented secrets handling | Externalize all configuration to environment variables (database URL, port, log level); document how secrets are stored and injected; commit an example env file with no real values | Config documented in README; no secrets in the repo; deployment reads config from the environment | 5 (Environment configuration and release safety) |

---

## 12. Documentation

Assessment section F (20 points) plus the submission package requirements.

| # | Requirement | Implementation task | Test / demo evidence | Points |
|---|---|---|---|---|
| [~] DC-1 | README with clean setup, run, test and deployment instructions | Write the README covering prerequisites, Android build/run, backend setup, migrations, running tests, Docker, and deployment — verified from a clean clone | Follow the README from a clean clone and confirm every step works | 5 |
| DC-2 | System architecture diagram | Produce the architecture diagram (Android app, Node.js service, database, cloud hosting, REST + WebSocket paths) | Committed under the architecture docs directory | 4 |
| DC-3 | Audio-flow diagram | Diagram the mandated path: Microphone → Oboe input stream → Effect processing → Encoding/file writer → Local Draft storage → Playback | Committed under the architecture docs directory | 3 |
| DC-4 | Room and WebSocket event-flow diagram | Diagram connect, join, share, disconnect and reconnect flows across all seven mandatory events | Committed under the architecture docs directory | 3 |
| DC-5 | Spin state-machine / sequence diagram | Diagram `WAITING → RUNNING → COMPLETED` with the `→ ABORTED` branch, plus the elimination sequence over time | Committed under the architecture docs directory | 3 |
| DC-6 | Assumptions, edge cases, trade-offs and known limitations | Write the reasoning document: every assumption made, each implemented edge case with its chosen outcome and why, trade-offs taken, and known limitations | Directly supports the C4 quality rule (explainable outcomes) and the reasoning interview | 2 |
| DC-7 | API documentation using OpenAPI/Swagger or equivalent | Document every endpoint in D1 with request/response schemas and error codes | Committed API spec; submission checklist item | part of the submission package |
| [~] DC-8 | Private Git repository with meaningful commits | Work in a private repository with incremental, meaningful commits; grant reviewer access | Commit history; submission checklist item "Private repository access granted" | part of the submission package |
| [x] DC-9 | Repository structure | Lay out the repository per the assessment's recommended structure (see ARCHITECTURE.md) | Repository tree matches the recommendation | part of the submission package |

---

## 13. Demo

Assessment section 9 (Demonstration Checklist) and section 10 (Candidate Submission Checklist). The
required recording is 5–10 minutes covering audio, room, spin wheel and cloud deployment.

### Demonstration checklist — all eleven items

| # | Demo item | What must be visible | Backed by |
|---|---|---|---|
| DM-1 | Record a voice clip using the Oboe path | Live recording through the Oboe input stream | AA-1, AA-2 |
| DM-2 | Apply Echo, Reverb or Pitch Shift | The processed clip played back audibly different from the dry clip | AA-5 |
| DM-3 | Save, list, play and delete Drafts | Full Draft lifecycle on screen with name, creation time and duration | DR-1..DR-3 |
| DM-4 | Create a room and join from a second client | Two clients in the same room | RM-1, BA-1, BA-2 |
| DM-5 | Show `user_joined`, `user_left` and `draft_shared` events | Events arriving at the second client | WS-1, WS-2, WS-3 |
| DM-6 | Start a spin with at least 3 users | Three eligible participants and a successful owner-initiated start | SP-1, BA-6 |
| DM-7 | Show elimination events every 5 seconds and exactly one winner | Timed eliminations and a single `winner_announced` | SP-2, SP-3, WS-5, WS-6 |
| DM-8 | Demonstrate one reconnect or state-recovery flow | A client drops and re-synchronizes via `room_state` | WS-7, WS-10, SP-10 |
| DM-9 | Demonstrate at least three implemented edge cases | Three edge cases triggered live with the outcome explained | SP-8..SP-17 (aim for the ≥5 documented band) |
| DM-10 | Show automated tests, hosted endpoint and CI/CD evidence | Test run output, live cloud endpoint, pipeline run | TS-1, CL-1, CD-1 |
| DM-11 | Trigger one expected failure and explain the handling | A deliberate failure (e.g. permission denied, invalid operation) handled gracefully | AA-4, RM-3, BA-9 |

### Submission package

| # | Item | Status field to fill |
|---|---|---|
| SB-1 | Private repository access granted | Repository link |
| SB-2 | Demo recording (5–10 minutes) covering audio, room, spin wheel and cloud deployment | Demo recording link |
| SB-3 | Cloud provider and endpoint recorded | Cloud provider and endpoint |
| SB-4 | Android device used recorded | Android device |
| SB-5 | Implemented effect recorded | Implemented effect |
| SB-6 | Handled edge cases listed | Handled edge cases |
| SB-7 | Self-assessed score against the 200-point rubric | Self-assessed score / 200 |
| SB-8 | Candidate name and role applied for | Candidate name, Role applied for |

---

## Suggested build order

The assessment weights correctness and reasoning over feature volume, and the largest single block
(C, 50 points) depends on the backend but not on Android. A dependency-respecting order:

1. **Database + Backend APIs skeleton** (DB-1..DB-4, BA-1..BA-5, BA-8) — unblocks everything server-side.
2. **WebSockets** (WS-1..WS-3, WS-7..WS-9) — room events working with two test clients.
3. **Spin Engine** (SP-1..SP-7, WS-4..WS-6) — the highest-value block.
4. **Edge cases + tests** (SP-8..SP-17, TS-1..TS-4) — 15 points in C4 plus 5 in D3, cheap once the engine exists.
5. **Docker → CI/CD → Cloud** (DK-1, CD-1, CL-1, EN-1) — the cloud deployment is a hard submission gate.
6. **Android Audio + Drafts** (AA-1..AA-7, DR-1..DR-6) — 40 points, independent of the server except DR-6.
7. **Documentation + Demo** (DC-1..DC-9, DM-1..DM-11) — 20 points, and the evidence that converts the rest into score.

---

## Phase log

### Phase 1 — Project initialization and backend foundation (complete)

Repository skeleton, git initialization, and a Node.js + TypeScript backend foundation on Express,
Socket.IO and Mongoose. No business logic, no database models, no Android work.

| Item | Status | What exists / what is still missing |
|---|---|---|
| DC-9 | `[x]` | Repository laid out per the assessment's recommended structure |
| BA-8 | `[x]` | `GET /health` (liveness, never queries MongoDB) and `GET /ready` (200 connected / 503 unavailable), both tested. Note CL-3 — documenting health verification for the *cloud* deployment — remains unstarted |
| BA-9 | `[~]` | Consistent error envelope, centralized error handler, 404 handling, Zod-validated environment config. No business-rule validation and no idempotency yet |
| BA-10 | `[~]` | Layered layout (routes → controllers → services → repositories) and structured Pino logging with request logs. `services/` and `repositories/` are still empty |
| WS-8 | `[~]` | Socket.IO initializes and logs connect/disconnect. No socket↔user↔room mapping, no presence cleanup |
| TS-1 | `[~]` | Vitest + Supertest harness with 6 passing tests (health, both ready branches, 404, error envelope, Socket.IO smoke). No model, room or spin tests |
| DK-1 | `[x]` | Multi-stage Dockerfile (non-root `node` user, pinned `node:22-alpine`, `HEALTHCHECK` on `/ready`). Image builds (64MB) and runs; container reports `healthy` |
| DK-2 | `[x]` | `infrastructure/docker-compose.yml` brings up MongoDB + backend together; verified end to end. Host port overridable via `BACKEND_PORT` |
| EN-1 | `[~]` | All config via environment variables, validated at startup; `.env.example` with placeholders; `.env` git-ignored. Cloud secrets handling not yet documented |
| DC-1 | `[~]` | README covers setup, env vars, running, testing, typechecking, building and Docker for what exists today |
| DC-8 | `[~]` | Local `git init` done. Creating the private remote and granting reviewer access is outstanding |

**Verified by (full pass, 2026-09-15):**

- Static: `npm run typecheck`, `npm run lint`, `npm test` (6/6), `npm run build` (14 modules) — all clean
- Live, against MongoDB in Docker: `GET /health` 200, `GET /ready` 200, `GET /nope` 404 in the error envelope
- Probe independence: with MongoDB stopped, `/health` stayed 200 while `/ready` returned 503 and the
  process stayed alive; after restarting MongoDB, `/ready` recovered to 200 with no app restart
- Socket.IO: a real `socket.io-client` connected over the websocket transport; server logged connect and disconnect
- Startup failures: missing `MONGODB_URI`, unreachable database, and an occupied port each log a
  structured fatal and exit 1
- Docker: image builds (64MB, runs as non-root `node`), full compose stack runs, container reports `healthy`
  via the `/ready` HEALTHCHECK, and all endpoints answer correctly through the container

**Still outstanding for later phases:** CI/CD pipeline, cloud deployment, and the private Git remote.

### Phase 2 — Database design and Mongoose models (not started)

DB-1..DB-5 and the repository layer, per ARCHITECTURE.md §3.
