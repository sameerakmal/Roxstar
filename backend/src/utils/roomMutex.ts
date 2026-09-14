// Serializes work for one room, implementing ARCHITECTURE §1.3's "one writer per room".
//
// This is an ORDERING optimization, not the correctness guarantee. Every invariant it
// helps with is independently enforced by MongoDB — unique partial indexes, conditional
// updates and unique constraints — so correctness survives even though this mutex is
// process-local and would not span multiple instances.
//
// Implemented as a promise chain per room: each task waits for the previous one, and
// the chain entry is removed once it drains so the map does not grow without bound.
const chains = new Map<string, Promise<unknown>>();

export async function withRoomLock<T>(roomId: string, task: () => Promise<T>): Promise<T> {
  const previous = chains.get(roomId) ?? Promise.resolve();

  // Swallow the predecessor's rejection so one failed task cannot poison the queue.
  const run = previous.then(task, task);

  chains.set(
    roomId,
    run.catch(() => undefined),
  );

  try {
    return await run;
  } finally {
    // Only clear if no newer task queued behind this one.
    void Promise.resolve().then(() => {
      const current = chains.get(roomId);
      if (current !== undefined) {
        void current.then(() => {
          if (chains.get(roomId) === current) {
            chains.delete(roomId);
          }
        });
      }
    });
  }
}

export function pendingRoomLocks(): number {
  return chains.size;
}
