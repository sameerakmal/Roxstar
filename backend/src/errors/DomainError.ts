// Domain errors describe what went wrong in business terms and carry a stable code.
// They deliberately know nothing about HTTP: the error-handling middleware owns the
// mapping from code to status, so services stay transport-free.
export abstract class DomainError extends Error {
  abstract readonly code: string;

  protected constructor(message: string) {
    super(message);
    this.name = new.target.name;
    Error.captureStackTrace(this, new.target);
  }
}

export class UserNotFoundError extends DomainError {
  readonly code = 'UNKNOWN_USER';

  constructor(userId: string) {
    super(`No user exists with id ${userId}`);
  }
}

export class RoomNotFoundError extends DomainError {
  readonly code = 'ROOM_NOT_FOUND';

  constructor(roomId: string) {
    super(`No room exists with id ${roomId}`);
  }
}

export class RoomClosedError extends DomainError {
  readonly code = 'ROOM_CLOSED';

  constructor(roomId: string) {
    super(`Room ${roomId} is closed and no longer accepts this operation`);
  }
}

export class NotAMemberError extends DomainError {
  readonly code = 'NOT_A_MEMBER';

  constructor(roomId: string) {
    super(`Caller is not an active member of room ${roomId}`);
  }
}

export class DraftNotFoundError extends DomainError {
  readonly code = 'DRAFT_NOT_FOUND';

  constructor(draftId: string) {
    super(`No draft exists with id ${draftId}`);
  }
}

export class DraftNotOwnedError extends DomainError {
  readonly code = 'DRAFT_NOT_OWNED';

  constructor(draftId: string) {
    super(`Caller does not own draft ${draftId}`);
  }
}
