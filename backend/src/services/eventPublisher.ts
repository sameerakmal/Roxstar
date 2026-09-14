import { logger } from '../utils/logger.js';

// The seam between business logic and delivery.
//
// Services broadcast through this interface instead of importing the Socket.IO server,
// which keeps the service layer transport-free and unit-testable with a fake publisher.
// Delivery is best-effort by design: MongoDB is authoritative, and a broadcast that
// never arrives is repaired when the client adopts a room_state snapshot.
export type EventPublisher = {
  toRoom(roomId: string, event: string, payload: unknown): void;
};

// Used until the Socket.IO server is wired up, and in tests that do not care about
// delivery. Dropping events here is safe precisely because it is not the source of truth.
const noopPublisher: EventPublisher = {
  toRoom(): void {
    // intentionally empty
  },
};

let publisher: EventPublisher = noopPublisher;

export function setEventPublisher(next: EventPublisher): void {
  publisher = next;
}

export function resetEventPublisher(): void {
  publisher = noopPublisher;
}

// Never called before the authoritative write has been awaited — see the
// persist-then-broadcast rule. A delivery failure must not fail the operation whose
// state is already committed, so it is logged and swallowed.
export function publishToRoom(roomId: string, event: string, payload: unknown): void {
  try {
    publisher.toRoom(roomId, event, payload);
  } catch (error: unknown) {
    logger.error({ err: error, roomId, event }, 'Failed to broadcast event');
  }
}
