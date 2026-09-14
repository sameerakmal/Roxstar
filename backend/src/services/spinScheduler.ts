import { logger } from '../utils/logger.js';

// Owns the elimination timers. One timer per spin, keyed by spin id.
//
// Timers decide only WHEN to attempt the next elimination — never WHAT happened. The
// database decides that: a tick whose spin is no longer RUNNING is a harmless no-op,
// because the conditional update it drives matches nothing. That separation is what
// makes a stray or duplicated timer safe.

const DEFAULT_INTERVAL_MS = 5000;

const timers = new Map<string, NodeJS.Timeout>();
let intervalMs = DEFAULT_INTERVAL_MS;

export function setEliminationIntervalMs(value: number): void {
  intervalMs = value;
}

export function getEliminationIntervalMs(): number {
  return intervalMs;
}

// Deadline for elimination number `n` (1-based), measured from the spin's start.
// Recomputed absolutely on every tick so drift never accumulates: a tick that fires
// late simply produces a smaller — possibly zero — delay for the next one, instead of
// pushing the whole schedule back.
export function deadlineFor(startedAt: Date, eliminationNumber: number): number {
  return startedAt.getTime() + eliminationNumber * intervalMs;
}

export function delayUntil(deadlineMs: number, now: number = Date.now()): number {
  return Math.max(0, deadlineMs - now);
}

// Scheduling is idempotent: any existing timer for the spin is cleared first, so a
// duplicate timer for one spin cannot exist regardless of how many callers schedule.
export function schedule(spinId: string, delayMs: number, task: () => Promise<void>): void {
  cancel(spinId);

  const timer = setTimeout(() => {
    timers.delete(spinId);
    void task().catch((error: unknown) => {
      logger.error({ err: error, spinId }, 'Spin elimination tick failed');
    });
  }, delayMs);

  // Do not hold the event loop open purely for a spin timer.
  timer.unref?.();
  timers.set(spinId, timer);
}

export function cancel(spinId: string): void {
  const existing = timers.get(spinId);
  if (existing !== undefined) {
    clearTimeout(existing);
    timers.delete(spinId);
  }
}

export function hasTimer(spinId: string): boolean {
  return timers.has(spinId);
}

export function scheduledCount(): number {
  return timers.size;
}

// Used on shutdown and between tests so no timer outlives its owner.
export function cancelAll(): void {
  for (const timer of timers.values()) {
    clearTimeout(timer);
  }
  timers.clear();
}
