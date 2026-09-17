import type { Types } from 'mongoose';

import {
  ActiveSpinConflictError,
  InsufficientPlayersError,
  NotAMemberError,
  NotRoomOwnerError,
  RoomClosedError,
  RoomNotFoundError,
  SpinNotFoundError,
  TooManyPlayersError,
} from '../errors/DomainError.js';
import { ActiveSpinExistsError } from '../errors/RepositoryError.js';
import {
  roomMemberRepository,
  roomRepository,
  spinEventRepository,
  spinParticipantRepository,
  spinRepository,
} from '../repositories/index.js';
import type { SpinRecord } from '../repositories/spinRepository.js';
import { logger } from '../utils/logger.js';
import { withRoomLock } from '../utils/roomMutex.js';
import { ROOM_EVENTS } from '../websocket/events.js';
import type { SpinStateDto } from './dto.js';
import { publishToRoom } from './eventPublisher.js';
import { buildActiveSpinDto, buildSpinStateDto, remainingOf } from './spinProjection.js';
import * as scheduler from './spinScheduler.js';

export const MIN_PLAYERS = 3;
export const MAX_PLAYERS = 20;

export const ABORT_NO_PARTICIPANTS = 'NO_PARTICIPANTS_REMAINING';
export const ABORT_INCOMPLETE_START = 'RECOVERED_INCOMPLETE_START';

// Eligibility is membership, not connection state. A member whose socket has dropped
// stays eligible — that is exactly what reconnect exists for.
async function eligibleUserIds(roomId: Types.ObjectId): Promise<Types.ObjectId[]> {
  const members = await roomMemberRepository.findActiveMembers(roomId);
  return members.map((member) => member.userId);
}

// ---------------------------------------------------------------------------
// Persist-then-broadcast. Every helper below writes the event BEFORE publishing, and
// the publish call is the last statement. A state that was not persisted is never
// announced; a broadcast that fails leaves the database correct and clients repair
// themselves from room_state.
// ---------------------------------------------------------------------------

async function recordAndBroadcast(
  spin: SpinRecord,
  eventType: 'spin_started' | 'user_eliminated' | 'winner_announced' | 'spin_aborted',
  payload: Record<string, unknown>,
  broadcastEvent: string | null,
): Promise<number> {
  const event = await spinEventRepository.appendNextEvent(spin._id, eventType, payload);

  if (broadcastEvent !== null) {
    publishToRoom(spin.roomId.toString(), broadcastEvent, {
      ...payload,
      sequenceNumber: event.sequenceNumber,
    });
  }

  return event.sequenceNumber;
}

async function completeWithWinner(spin: SpinRecord, winnerUserId: Types.ObjectId): Promise<void> {
  await spinParticipantRepository.markWinner(spin._id, winnerUserId);

  // Compare-and-swap from RUNNING. Only one caller can win it, which is what makes the
  // winner announcement exactly-once even if a timer and a departure race here.
  const completed = await spinRepository.completeSpin(spin._id, winnerUserId);
  if (completed === null) {
    return;
  }

  scheduler.cancel(spin._id.toString());

  const active = await buildActiveSpinDto(completed);
  await recordAndBroadcast(
    completed,
    'winner_announced',
    {
      roomId: completed.roomId.toString(),
      spinId: completed._id.toString(),
      status: 'COMPLETED',
      winner: active.winner,
      completedAt: completed.completedAt,
    },
    ROOM_EVENTS.winnerAnnounced,
  );

  logger.info(
    {
      roomId: completed.roomId.toString(),
      spinId: completed._id.toString(),
      userId: winnerUserId.toString(),
    },
    'Spin completed with winner',
  );
}

async function abortSpin(spin: SpinRecord, reason: string): Promise<void> {
  const aborted = await spinRepository.abortSpin(spin._id, reason);
  if (aborted === null) {
    return;
  }

  scheduler.cancel(spin._id.toString());

  // spin_aborted is persisted for audit and recovery but is not one of the mandatory
  // broadcast events, so nothing is emitted for it.
  await recordAndBroadcast(
    aborted,
    'spin_aborted',
    { roomId: aborted.roomId.toString(), spinId: aborted._id.toString(), reason },
    null,
  );

  logger.info(
    {
      roomId: aborted.roomId.toString(),
      spinId: aborted._id.toString(),
      reason,
    },
    'Spin aborted',
  );
}

// One scheduled elimination. Safe to invoke spuriously: if the spin is no longer
// RUNNING every write below matches nothing.
async function runEliminationTick(spinId: Types.ObjectId): Promise<void> {
  const spin = await spinRepository.findSpinById(spinId);
  if (spin === null || spin.status !== 'RUNNING') {
    scheduler.cancel(spinId.toString());
    return;
  }

  await withRoomLock(spin.roomId.toString(), async () => {
    await eliminateOnce(spin);
  });

  await scheduleNextTick(spinId);
}

// The elimination step shared by the timer, recovery catch-up and departure handling.
async function eliminateOnce(spin: SpinRecord): Promise<void> {
  const outcome = await spinParticipantRepository.eliminateNextParticipant(spin._id);

  if (outcome.kind === 'NONE_REMAINING') {
    await abortSpin(spin, ABORT_NO_PARTICIPANTS);
    return;
  }

  if (outcome.kind === 'LAST_REMAINING') {
    await completeWithWinner(spin, outcome.userId);
    return;
  }

  const active = await buildActiveSpinDto(spin);
  const eliminated = active.participants.find(
    (player) => player.userId === outcome.participant.userId.toString(),
  );

  await recordAndBroadcast(
    spin,
    'user_eliminated',
    {
      roomId: spin.roomId.toString(),
      spinId: spin._id.toString(),
      eliminatedUser: eliminated ?? null,
      eliminationOrder: outcome.participant.eliminationOrder,
      remainingPlayers: remainingOf(active.participants),
    },
    ROOM_EVENTS.userEliminated,
  );

  logger.info(
    {
      roomId: spin.roomId.toString(),
      spinId: spin._id.toString(),
      userId: outcome.participant.userId.toString(),
      eliminationOrder: outcome.participant.eliminationOrder,
      reason: outcome.participant.eliminationReason,
      remainingCount: remainingOf(active.participants).length,
    },
    'Spin participant eliminated',
  );

  // Eliminating the second-to-last player leaves exactly one: complete immediately
  // rather than waiting another interval for a tick that has nothing to do.
  const stillActive = await spinParticipantRepository.findActiveUserIds(spin._id);
  if (stillActive.length === 1) {
    await completeWithWinner(spin, stillActive[0] as Types.ObjectId);
  }
}

// Schedules the next tick at an ABSOLUTE deadline derived from startedAt and the number
// of scheduled eliminations already applied, so drift cannot accumulate.
async function scheduleNextTick(spinId: Types.ObjectId): Promise<void> {
  const spin = await spinRepository.findSpinById(spinId);
  const startedAt = spin?.startedAt ?? null;
  if (spin === null || spin.status !== 'RUNNING' || startedAt === null) {
    scheduler.cancel(spinId.toString());
    return;
  }

  const timerEliminations = await spinParticipantRepository.countTimerEliminations(spinId);
  const deadline = scheduler.deadlineFor(startedAt, timerEliminations + 1);

  scheduler.schedule(spinId.toString(), scheduler.delayUntil(deadline), async () => {
    await runEliminationTick(spinId);
  });
}

export async function startSpin(
  roomId: Types.ObjectId,
  callerUserId: Types.ObjectId,
): Promise<SpinStateDto> {
  return withRoomLock(roomId.toString(), async () => {
    const room = await roomRepository.findRoomById(roomId);
    if (room === null) {
      throw new RoomNotFoundError(roomId.toString());
    }
    if (room.status !== 'ACTIVE') {
      throw new RoomClosedError(roomId.toString());
    }
    if (room.ownerUserId.toString() !== callerUserId.toString()) {
      throw new NotRoomOwnerError(roomId.toString());
    }

    // Unconditional insert: the unique partial index — not a prior read — is what
    // stops two concurrent starts from both succeeding.
    let spin: SpinRecord;
    try {
      spin = await spinRepository.createActiveSpin(roomId, callerUserId);
    } catch (error: unknown) {
      if (error instanceof ActiveSpinExistsError) {
        throw new ActiveSpinConflictError(roomId.toString());
      }
      throw error;
    }

    const userIds = await eligibleUserIds(roomId);

    // Aborting a failed start frees the room immediately rather than leaving a WAITING
    // spin that would block every future start.
    if (userIds.length < MIN_PLAYERS) {
      await spinRepository.abortSpin(spin._id, 'INSUFFICIENT_PLAYERS');
      throw new InsufficientPlayersError(userIds.length, MIN_PLAYERS);
    }
    if (userIds.length > MAX_PLAYERS) {
      await spinRepository.abortSpin(spin._id, 'TOO_MANY_PLAYERS');
      throw new TooManyPlayersError(userIds.length, MAX_PLAYERS);
    }

    await spinParticipantRepository.addParticipants(spin._id, userIds);
    await spinParticipantRepository.activateParticipants(spin._id);

    const running = await spinRepository.transitionSpinStatus(spin._id, 'WAITING', 'RUNNING', {
      startedAt: new Date(),
    });
    if (running === null) {
      throw new ActiveSpinConflictError(roomId.toString());
    }

    const active = await buildActiveSpinDto(running);
    await recordAndBroadcast(
      running,
      'spin_started',
      {
        roomId: running.roomId.toString(),
        spinId: running._id.toString(),
        status: 'RUNNING',
        startedAt: running.startedAt,
        eligiblePlayers: active.participants,
        remainingPlayers: remainingOf(active.participants),
      },
      ROOM_EVENTS.spinStarted,
    );

    logger.info(
      {
        roomId: running.roomId.toString(),
        spinId: running._id.toString(),
        userId: callerUserId.toString(),
        eligibleCount: active.participants.length,
      },
      'Spin started',
    );

    await scheduleNextTick(running._id);

    return buildSpinStateDto(running);
  });
}

export async function getSpinState(
  spinId: Types.ObjectId,
  callerUserId: Types.ObjectId,
): Promise<SpinStateDto> {
  const spin = await spinRepository.findSpinById(spinId);
  if (spin === null) {
    throw new SpinNotFoundError(spinId.toString());
  }

  const membership = await roomMemberRepository.findActiveMembership(spin.roomId, callerUserId);
  if (membership === null) {
    throw new NotAMemberError(spin.roomId.toString());
  }

  return buildSpinStateDto(spin);
}

// A member who explicitly LEAVES during a running spin is eliminated immediately, with
// reason LEFT so recovery does not mistake it for a scheduled tick. A mere disconnect
// does NOT reach here — that is what makes reconnect possible.
export async function handleParticipantLeft(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<void> {
  // Serialized against elimination ticks. Callers must not already hold the room lock.
  return withRoomLock(roomId.toString(), async () => {
    await applyParticipantLeft(roomId, userId);
  });
}

async function applyParticipantLeft(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<void> {
  const spin = await spinRepository.findActiveSpin(roomId);
  if (spin === null || spin.status !== 'RUNNING') {
    return;
  }

  const eliminated = await spinParticipantRepository.eliminateParticipantForLeave(spin._id, userId);
  if (eliminated === null) {
    return;
  }

  const active = await buildActiveSpinDto(spin);
  const player = active.participants.find((p) => p.userId === userId.toString());

  await recordAndBroadcast(
    spin,
    'user_eliminated',
    {
      roomId: spin.roomId.toString(),
      spinId: spin._id.toString(),
      eliminatedUser: player ?? null,
      eliminationOrder: eliminated.eliminationOrder,
      remainingPlayers: remainingOf(active.participants),
    },
    ROOM_EVENTS.userEliminated,
  );

  logger.info(
    {
      roomId: spin.roomId.toString(),
      spinId: spin._id.toString(),
      userId: userId.toString(),
      eliminationOrder: eliminated.eliminationOrder,
      reason: 'LEFT',
      remainingCount: remainingOf(active.participants).length,
    },
    'Spin participant eliminated on leave',
  );

  const stillActive = await spinParticipantRepository.findActiveUserIds(spin._id);
  if (stillActive.length === 1) {
    await completeWithWinner(spin, stillActive[0] as Types.ObjectId);
  } else if (stillActive.length === 0) {
    await abortSpin(spin, ABORT_NO_PARTICIPANTS);
  } else {
    // The schedule is unchanged: a LEFT elimination does not consume a timer tick.
    await scheduleNextTick(spin._id);
  }
}

// Exposed for recovery.
export { eliminateOnce, scheduleNextTick, abortSpin };

export function stopAllSpinTimers(): void {
  scheduler.cancelAll();
  logger.debug('All spin timers cancelled');
}
