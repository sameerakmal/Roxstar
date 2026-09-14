// Tracks which live sockets belong to which user in which room.
//
// This is NOT a second source of truth for membership — MongoDB owns that. What lives
// here is the set of open TCP connections, which cannot be persisted meaningfully: a
// stored socket id is meaningless after a restart. Only the derived CONNECTED /
// DISCONNECTED flag is written back to MongoDB for REST snapshots.
//
// Single-instance by design, consistent with the assessment excluding Kubernetes and
// multi-instance deployment. A multi-instance setup would need the Socket.IO Redis
// adapter, which is out of scope.

type RoomPresence = Map<string, Set<string>>; // userId -> socketIds

const rooms = new Map<string, RoomPresence>();

export type PresenceChange = 'FIRST_CONNECTION' | 'ADDITIONAL_CONNECTION';
export type PresenceRemoval = 'LAST_DISCONNECTION' | 'REMAINING_CONNECTIONS' | 'NOT_PRESENT';

// Returns FIRST_CONNECTION only when this is the user's first socket in the room, so
// callers know when presence actually changed and a broadcast is warranted.
export function addSocket(roomId: string, userId: string, socketId: string): PresenceChange {
  let room = rooms.get(roomId);
  if (room === undefined) {
    room = new Map<string, Set<string>>();
    rooms.set(roomId, room);
  }

  let sockets = room.get(userId);
  if (sockets === undefined) {
    sockets = new Set<string>();
    room.set(userId, sockets);
  }

  const wasEmpty = sockets.size === 0;
  sockets.add(socketId);

  return wasEmpty ? 'FIRST_CONNECTION' : 'ADDITIONAL_CONNECTION';
}

// Returns LAST_DISCONNECTION only when the user has no sockets left in the room. A
// second tab closing therefore produces no user_left.
export function removeSocket(roomId: string, userId: string, socketId: string): PresenceRemoval {
  const room = rooms.get(roomId);
  const sockets = room?.get(userId);

  if (room === undefined || sockets === undefined || !sockets.delete(socketId)) {
    return 'NOT_PRESENT';
  }

  if (sockets.size > 0) {
    return 'REMAINING_CONNECTIONS';
  }

  room.delete(userId);
  if (room.size === 0) {
    rooms.delete(roomId);
  }
  return 'LAST_DISCONNECTION';
}

export function connectionCount(roomId: string, userId: string): number {
  return rooms.get(roomId)?.get(userId)?.size ?? 0;
}

export function isConnected(roomId: string, userId: string): boolean {
  return connectionCount(roomId, userId) > 0;
}

export function roomsForSocketCleanup(): string[] {
  return [...rooms.keys()];
}

export function clearPresence(): void {
  rooms.clear();
}
