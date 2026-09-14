import { roomMemberRepository, spinParticipantRepository, spinRepository } from '../repositories/index.js';
import type { SpinRecord } from '../repositories/spinRepository.js';
import { logger } from '../utils/logger.js';
import {
  ABORT_INCOMPLETE_START,
  ABORT_NO_PARTICIPANTS,
  abortSpin,
  eliminateOnce,
  scheduleNextTick,
} from './spinService.js';
import { getEliminationIntervalMs } from './spinScheduler.js';

export type RecoveryReport = {
  disconnectedMembers: number;
  abortedWaitingSpins: number;
  resumedSpins: number;
  catchUpEliminations: number;
};

// How many scheduled eliminations are still owed, derived from PERSISTED STATE rather
// than from elapsed time alone.
//
// The subtraction uses only TIMER eliminations. A participant removed because they LEFT
// did not consume a 5-second tick, so counting them would make recovery believe a
// scheduled elimination had happened and silently skip one.
//
// The result is clamped to [0, activeCount - 1] so recovery can never run past the
// point where one winner remains, however long the process was down.
export function pendingEliminations(input: {
  startedAt: Date;
  now: number;
  intervalMs: number;
  timerEliminatedCount: number;
  activeCount: number;
}): number {
  const due = Math.floor((input.now - input.startedAt.getTime()) / input.intervalMs);
  const owed = due - input.timerEliminatedCount;
  const maximum = Math.max(0, input.activeCount - 1);

  return Math.min(Math.max(owed, 0), maximum);
}

async function recoverRunningSpin(spin: SpinRecord): Promise<number> {
  const startedAt = spin.startedAt ?? null;
  if (startedAt === null) {
    // RUNNING without a start time cannot be reasoned about; free the room.
    await abortSpin(spin, ABORT_INCOMPLETE_START);
    return 0;
  }

  const activeCount = (await spinParticipantRepository.findActiveUserIds(spin._id)).length;

  if (activeCount === 0) {
    await abortSpin(spin, ABORT_NO_PARTICIPANTS);
    return 0;
  }

  // One active participant already means the spin is decided; complete it rather than
  // scheduling a tick with nothing to do.
  if (activeCount === 1) {
    await eliminateOnce(spin);
    return 0;
  }

  const timerEliminatedCount = await spinParticipantRepository.countTimerEliminations(spin._id);
  const pending = pendingEliminations({
    startedAt,
    now: Date.now(),
    intervalMs: getEliminationIntervalMs(),
    timerEliminatedCount,
    activeCount,
  });

  // Each step re-reads live state, so an already-eliminated participant is never
  // eliminated again and the loop stops as soon as a winner emerges.
  let applied = 0;
  for (let index = 0; index < pending; index += 1) {
    const current = await spinRepository.findSpinById(spin._id);
    if (current === null || current.status !== 'RUNNING') {
      break;
    }
    await eliminateOnce(current);
    applied += 1;
  }

  await scheduleNextTick(spin._id);
  return applied;
}

// Runs once at startup, before the server accepts traffic. Safe to run more than once:
// every decision is derived from current persisted state, so a second run over an
// already-recovered spin computes zero pending work.
export async function recoverSpins(): Promise<RecoveryReport> {
  const report: RecoveryReport = {
    disconnectedMembers: 0,
    abortedWaitingSpins: 0,
    resumedSpins: 0,
    catchUpEliminations: 0,
  };

  // No sockets exist after a restart, so every persisted CONNECTED flag is stale.
  // Membership is untouched.
  report.disconnectedMembers = await roomMemberRepository.markAllDisconnected();

  const activeSpins = await spinRepository.findAllActiveSpins();

  for (const spin of activeSpins) {
    if (spin.status === 'WAITING') {
      // A WAITING spin is a crash orphan: its starter is gone and it can never be
      // started, yet it occupies the room's single active-spin slot.
      await abortSpin(spin, ABORT_INCOMPLETE_START);
      report.abortedWaitingSpins += 1;
      continue;
    }

    report.catchUpEliminations += await recoverRunningSpin(spin);
    report.resumedSpins += 1;
  }

  if (activeSpins.length > 0 || report.disconnectedMembers > 0) {
    logger.info({ ...report }, 'Spin recovery complete');
  }

  return report;
}
