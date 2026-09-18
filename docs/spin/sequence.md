# Spin Wheel Event Sequence & Interaction Specification

This document presents the detailed execution sequence, message timing, and interaction flows for the **Multiplayer Spin Wheel** engine.

---

## 1. Overview

The spin wheel operates as a server-authoritative, timed elimination game. The server manages all participant state transitions, elimination selections, 5-second tick resolution, database persistence, and Socket.IO broadcasts. Clients render UI animations based exclusively on server-issued events.

---

## 2. End-to-End Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor Owner as Room Owner
    actor Client2 as Participant B
    actor Client3 as Participant C
    participant Express as Express REST Controller
    participant Engine as SpinEngine Service
    participant Mongo as MongoDB Atlas
    participant Socket as Socket.IO Server

    Owner->>Express: POST /rooms/:roomId/spins (x-user-id: Owner)
    Express->>Engine: startSpin(roomId, userId)
    Engine->>Mongo: Query room members & active spins
    
    alt Insufficient Players (<3 or >20) or Active Spin Exists
        Engine-->>Express: Return Validation Error / 409 Conflict
        Express-->>Owner: HTTP 400/409 Error Envelope
    else Valid Request
        Engine->>Mongo: Insert Spin document (WAITING status)
        Note over Mongo: Unique Partial Index verifies single active spin
        Engine->>Mongo: Snapshot eligible participants (Status = ACTIVE)
        Engine->>Mongo: Update Spin status to RUNNING
        Engine->>Mongo: Insert SpinEvent (SPIN_STARTED, seq=1)
        Engine->>Socket: Broadcast 'spin_started' to Room
        Socket-->>Owner: 'spin_started' event (sequence=1)
        Socket-->>Client2: 'spin_started' event (sequence=1)
        Socket-->>Client3: 'spin_started' event (sequence=1)

        loop Every 5 Seconds (Server Elimination Cadence)
            Engine->>Engine: Timer fires (Absolute deadline target)
            Engine->>Engine: Select 1 random ACTIVE participant
            Engine->>Mongo: CAS Update participant state to ELIMINATED (reason: TIMER)
            Engine->>Mongo: Insert SpinEvent (USER_ELIMINATED, seq=N)
            Engine->>Socket: Broadcast 'user_eliminated' to Room
            Socket-->>Owner: 'user_eliminated' (eliminatedUser, remainingPlayers, seq=N)
            Socket-->>Client2: 'user_eliminated' (eliminatedUser, remainingPlayers, seq=N)
            Socket-->>Client3: 'user_eliminated' (eliminatedUser, remainingPlayers, seq=N)
        end

        Note over Engine: Exactly 1 ACTIVE participant remains
        Engine->>Mongo: CAS Update participant state to WINNER
        Engine->>Mongo: Update Spin status to COMPLETED (winnerUserId)
        Engine->>Mongo: Insert SpinEvent (WINNER_ANNOUNCED, seq=Final)
        Engine->>Socket: Broadcast 'winner_announced' to Room
        Socket-->>Owner: 'winner_announced' (winner, seq=Final)
        Socket-->>Client2: 'winner_announced' (winner, seq=Final)
        Socket-->>Client3: 'winner_announced' (winner, seq=Final)
        Express-->>Owner: HTTP 201 Created (Spin DTO)
    end
```

---

## 3. Sequence Step Descriptions

### 3.1 Initiation & Pre-Checks
1. The room owner issues `POST /rooms/:roomId/spins`.
2. The `SpinService` validates that the caller matches `Room.ownerId`.
3. The count of active room members (`membershipState = 'JOINED'`) is verified to be between **3 and 20**.

### 3.2 Atomic Insertion & Event Emission
4. The server creates a `Spin` document with `status = 'WAITING'`. A unique partial index on `Spin{ roomId }` for active statuses (`WAITING`, `RUNNING`) guarantees that duplicate concurrent requests fail atomically with a `409 Conflict` database exception.
5. All current joined members are snapshot as `SpinParticipant` records with status `ACTIVE`.
6. The spin transitions to `RUNNING`, and a `spin_started` event (`sequenceNumber = 1`) is recorded in `SpinEvent` and broadcast over Socket.IO.

### 3.3 Timed Elimination Loop
7. A `setTimeout` schedule runs with a **5000 ms cadence** (configurable in environment).
8. On each tick, the engine randomly selects one active participant, assigns an explicit `eliminationOrder` integer, and sets their status to `ELIMINATED` (`eliminationReason = 'TIMER'`).
9. A `user_eliminated` event is logged to MongoDB and broadcast to all room sockets with the updated remaining player list.

### 3.4 Completion & Winner Declaration
10. When the active participant count reaches exactly 1, the loop terminates.
11. The final participant is marked as `WINNER`, the spin status transitions to `COMPLETED`, and `completedAt` is timestamped.
12. A `winner_announced` payload carrying the winner DTO and sequence number is emitted to all room participants.
