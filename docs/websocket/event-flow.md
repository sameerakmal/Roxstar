# Real-Time WebSocket & Event Flow

This document details the Socket.IO real-time communication architecture between the Android client and the Node.js backend, covering connection lifecycle, presence tracking, and all 7 mandatory room events.

---

## 1. Socket.IO Architecture Overview

```mermaid
graph LR
    AndroidClient["Android Client<br/>(RoomSocketClient)"]
    Gateway["Socket.IO Server<br/>(handlers.ts)"]
    Presence["PresenceRegistry<br/>(In-memory socket map)"]
    Service["Room / Spin Services<br/>(Authoritative State)"]
    RoomMembers["MongoDB<br/>(Authoritative Membership)"]

    AndroidClient -->|1. Connect with userId| Gateway
    Gateway -->|2. Register socket| Presence
    AndroidClient -->|3. emit('join_room', roomId)| Gateway
    Gateway -->|4. Verify REST membership| RoomMembers
    Gateway -->|5. socket.join(roomId)| Gateway
    Gateway -->|6. emit('room_state')| AndroidClient
    Service -->|7. Broadcast room event| Gateway
    Gateway -->|8. Push event| AndroidClient
```

### 1.1 Separation of Concerns
- **REST owns membership**: A client joins or leaves a room via REST endpoints (`POST /rooms/:id/join`, `POST /rooms/:id/leave`). Sockets never mutate database membership.
- **WebSocket carries events and presence**: Socket.IO handles live room presence, broadcasts mutations performed via REST, and streams spin wheel events. Live audio is never streamed over WebSockets.

---

## 2. Connection, Authentication & Presence

### 2.1 Connection Handshake
When the Android client initializes `RoomSocketClient`:
1. Connects to the backend via Socket.IO with the caller's identity passed in the query or auth payload:
   ```json
   { "auth": { "userId": "<24-hex-objectId>" } }
   ```
2. The server middleware (`authMiddleware.ts`) validates that `userId` is a valid MongoDB ObjectId.

### 2.2 Presence Registry vs Membership
- **Room Membership** (`RoomMember` in MongoDB): Represents the persistent state (`JOINED` or `LEFT`).
- **Presence State** (`PresenceRegistry` in Node.js memory): Tracks active socket IDs connected per room per user.
- **Rule on Disconnect**: A dropped socket connection updates `connectionState = 'DISCONNECTED'` in presence only. **It does NOT eliminate the user from an ongoing spin or revoke room membership.** This enables clients to drop and reconnect without losing their position in the game.

---

## 3. Mandatory Room Events (Specification & Payloads)

The system implements all seven mandatory real-time events required by the assessment:

### 3.1 `user_joined`
Emitted to the room when a user joins the room or connects their first active socket for an existing membership.

```json
{
  "roomId": "66e85bc7b39a3f2c5d123456",
  "user": {
    "userId": "66e85bc7b39a3f2c5d654321",
    "displayName": "Alice"
  },
  "participantCount": 3,
  "participants": [
    {
      "userId": "66e85bc7b39a3f2c5d654321",
      "displayName": "Alice",
      "membershipState": "JOINED",
      "connectionState": "CONNECTED",
      "joinedAt": "2026-09-17T10:00:00.000Z"
    }
  ]
}
```

### 3.2 `user_left`
Emitted to the room when a user explicitly leaves the room via REST or when their last active socket disconnects.

```json
{
  "roomId": "66e85bc7b39a3f2c5d123456",
  "user": {
    "userId": "66e85bc7b39a3f2c5d654321",
    "displayName": "Alice"
  },
  "participantCount": 2,
  "participants": [...]
}
```

### 3.3 `draft_shared`
Emitted to the room when a member shares a local voice draft via `POST /rooms/:roomId/drafts`.

```json
{
  "roomId": "66e85bc7b39a3f2c5d123456",
  "draft": {
    "draftId": "66e85bc7b39a3f2c5d888888",
    "name": "Guitar Riff",
    "durationMs": 14200,
    "effect": "ECHO",
    "fileLocation": "drafts/guitar_riff.wav",
    "sharedByUserId": "66e85bc7b39a3f2c5d654321",
    "sharedAt": "2026-09-17T10:05:00.000Z"
  },
  "user": {
    "userId": "66e85bc7b39a3f2c5d654321",
    "displayName": "Alice"
  }
}
```

### 3.4 `spin_started`
Emitted when the room owner initiates a spin via `POST /rooms/:roomId/spins`. Contains the initial sequence state and all eligible participants.

```json
{
  "roomId": "66e85bc7b39a3f2c5d123456",
  "spinId": "66e85bc7b39a3f2c5d999999",
  "status": "RUNNING",
  "eligiblePlayers": [
    {
      "userId": "66e85bc7b39a3f2c5d654321",
      "displayName": "Alice",
      "status": "ACTIVE",
      "eliminationOrder": null,
      "eliminationReason": null
    },
    {
      "userId": "66e85bc7b39a3f2c5d654322",
      "displayName": "Bob",
      "status": "ACTIVE",
      "eliminationOrder": null,
      "eliminationReason": null
    },
    {
      "userId": "66e85bc7b39a3f2c5d654323",
      "displayName": "Charlie",
      "status": "ACTIVE",
      "eliminationOrder": null,
      "eliminationReason": null
    }
  ],
  "sequenceNumber": 1,
  "startedAt": "2026-09-17T10:10:00.000Z"
}
```

### 3.5 `user_eliminated`
Emitted at each scheduled elimination tick (or when an active participant departs the room mid-spin).

```json
{
  "roomId": "66e85bc7b39a3f2c5d123456",
  "spinId": "66e85bc7b39a3f2c5d999999",
  "eliminatedUser": {
    "userId": "66e85bc7b39a3f2c5d654322",
    "displayName": "Bob",
    "status": "ELIMINATED",
    "eliminationOrder": 1,
    "eliminationReason": "TIMER"
  },
  "remainingPlayers": [
    {
      "userId": "66e85bc7b39a3f2c5d654321",
      "displayName": "Alice",
      "status": "ACTIVE"
    },
    {
      "userId": "66e85bc7b39a3f2c5d654323",
      "displayName": "Charlie",
      "status": "ACTIVE"
    }
  ],
  "sequenceNumber": 2,
  "eliminationReason": "TIMER"
}
```

### 3.6 `winner_announced`
Emitted exactly once when only one participant remains active in the spin.

```json
{
  "roomId": "66e85bc7b39a3f2c5d123456",
  "spinId": "66e85bc7b39a3f2c5d999999",
  "status": "COMPLETED",
  "winner": {
    "userId": "66e85bc7b39a3f2c5d654321",
    "displayName": "Alice",
    "status": "WINNER",
    "eliminationOrder": null,
    "eliminationReason": null
  },
  "sequenceNumber": 3,
  "completedAt": "2026-09-17T10:10:10.000Z"
}
```

### 3.7 `room_state` (Authoritative Snapshot)
Sent directly to a connecting or reconnecting client upon joining the room socket channel. Contains the authoritative snapshot of the room, participants, shared drafts, and active spin state.

```json
{
  "room": {
    "id": "66e85bc7b39a3f2c5d123456",
    "status": "ACTIVE",
    "ownerUserId": "66e85bc7b39a3f2c5d654321",
    "createdAt": "2026-09-17T09:00:00.000Z",
    "updatedAt": "2026-09-17T10:10:00.000Z"
  },
  "participants": [...],
  "sharedDrafts": [...],
  "activeSpin": {
    "spinId": "66e85bc7b39a3f2c5d999999",
    "status": "RUNNING",
    "startedAt": "2026-09-17T10:10:00.000Z",
    "participants": [...],
    "remainingPlayers": [...],
    "winner": null,
    "lastSequenceNumber": 2
  }
}
```

---

## 4. Reconnection & State Recovery Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client as Android Client
    participant Server as Socket.IO Gateway
    participant DB as MongoDB

    Note over Client,Server: Client drops connection during an ongoing spin
    Server->>Server: Remove socket from PresenceRegistry
    Server->>DB: Mark connectionState = 'DISCONNECTED'
    Note over Server,DB: Spin continues uninterrupted; client is NOT eliminated

    Note over Client,Server: Client regains network and reconnects
    Client->>Server: Connect (userId)
    Client->>Server: emit("join_room", { roomId })
    Server->>DB: Verify active membership
    Server->>Server: Add socket to room channel & PresenceRegistry
    Server->>DB: Read authoritative room & active spin snapshot
    Server-->>Client: emit("room_state", fullSnapshot)
    Note over Client: Client reconstructs UI, wheel, and remaining players
    Server-->>Client: Stream subsequent 'user_eliminated' or 'winner_announced'
```
