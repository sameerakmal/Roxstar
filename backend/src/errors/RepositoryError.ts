const MONGO_DUPLICATE_KEY = 11000;

type MongoDuplicateKeyError = Error & {
  code: number;
  keyPattern?: Record<string, unknown>;
  message: string;
};

// Repository-level errors stay free of HTTP concerns. The API layer maps them to
// status codes; the spin engine reacts to them as domain outcomes.
export class DuplicateKeyError extends Error {
  readonly indexName: string;

  constructor(indexName: string, message: string) {
    super(message);
    this.name = 'DuplicateKeyError';
    this.indexName = indexName;
  }
}

// Raised when the unique partial index on Spin rejects a second active spin for a
// room. This is the duplicate-start outcome arriving from the database rather than
// from a read-then-write check, so it is race-proof by construction.
export class ActiveSpinExistsError extends Error {
  readonly roomId: string;

  constructor(roomId: string) {
    super(`Room ${roomId} already has an active spin`);
    this.name = 'ActiveSpinExistsError';
    this.roomId = roomId;
  }
}

export function isDuplicateKeyError(error: unknown): error is MongoDuplicateKeyError {
  return (
    error instanceof Error &&
    'code' in error &&
    (error as { code: unknown }).code === MONGO_DUPLICATE_KEY
  );
}

// The driver reports the violated index in the error message; extracting it lets
// callers tell one uniqueness rule from another without string matching at the call site.
export function duplicateKeyIndexName(error: MongoDuplicateKeyError): string {
  const match = /index:\s*(\S+)/.exec(error.message);
  return match?.[1] ?? 'unknown';
}
