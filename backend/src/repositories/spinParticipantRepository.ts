import type { Types } from 'mongoose';

import {
  DuplicateKeyError,
  duplicateKeyIndexName,
  isDuplicateKeyError,
} from '../errors/RepositoryError.js';
import {
  SpinParticipantModel,
  type EliminationReason,
  type SpinParticipant,
  type SpinParticipantStatus,
} from '../models/index.js';

export type SpinParticipantRecord = SpinParticipant & { _id: Types.ObjectId };

// Snapshots the eligible set at spin start. The unique (spinId, userId) index means a
// user cannot appear twice even if the same start is processed concurrently.
export async function addParticipants(
  spinId: Types.ObjectId,
  userIds: Types.ObjectId[],
): Promise<SpinParticipantRecord[]> {
  try {
    const created = await SpinParticipantModel.insertMany(
      userIds.map((userId) => ({ spinId, userId })),
      { ordered: true },
    );
    return created.map((participant) => participant.toObject());
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      throw new DuplicateKeyError(
        duplicateKeyIndexName(error),
        `Duplicate participant in spin ${spinId.toString()}`,
      );
    }
    throw error;
  }
}

export async function findParticipants(spinId: Types.ObjectId): Promise<SpinParticipantRecord[]> {
  return SpinParticipantModel.find({ spinId })
    .sort({ createdAt: 1 })
    .lean<SpinParticipantRecord[]>()
    .exec();
}

export async function findParticipantsByStatus(
  spinId: Types.ObjectId,
  status: SpinParticipantStatus,
): Promise<SpinParticipantRecord[]> {
  return SpinParticipantModel.find({ spinId, status })
    .lean<SpinParticipantRecord[]>()
    .exec();
}

export async function countParticipantsByStatus(
  spinId: Types.ObjectId,
  status: SpinParticipantStatus,
): Promise<number> {
  return SpinParticipantModel.countDocuments({ spinId, status }).exec();
}

// How many scheduled ticks have already been applied. Eliminations caused by a user
// LEAVING are excluded on purpose: they did not consume a 5-second tick, and counting
// them would make recovery skip a scheduled elimination.
export async function countTimerEliminations(spinId: Types.ObjectId): Promise<number> {
  return SpinParticipantModel.countDocuments({
    spinId,
    status: 'ELIMINATED',
    eliminationReason: 'TIMER',
  }).exec();
}

export async function findActiveUserIds(spinId: Types.ObjectId): Promise<Types.ObjectId[]> {
  const participants = await SpinParticipantModel.find({ spinId, status: 'ACTIVE' })
    .select('userId')
    .lean<{ userId: Types.ObjectId }[]>()
    .exec();
  return participants.map((participant) => participant.userId);
}

export async function activateParticipants(spinId: Types.ObjectId): Promise<number> {
  const result = await SpinParticipantModel.updateMany(
    { spinId, status: 'ELIGIBLE' },
    { $set: { status: 'ACTIVE' } },
  ).exec();
  return result.modifiedCount;
}

// The elimination primitive. Status, order, timestamp and reason are written by ONE
// conditional update on ONE document, so the forbidden intermediate states are
// unreachable without needing a transaction:
//
//   - ELIMINATED with no order      -> impossible, the same $set carries both
//   - an order consumed with no elimination -> impossible, there is no separate
//     allocation step to crash between
//   - the same participant eliminated twice -> impossible, the filter requires ACTIVE
//   - two participants sharing an order     -> rejected by uniq_elimination_order,
//     which leaves the document untouched so the retry simply recomputes
//
// The order is derived rather than read from a counter field. A losing race surfaces
// as a null match or a duplicate key, and is retried with fresh state.
async function applyElimination(
  spinId: Types.ObjectId,
  userId: Types.ObjectId,
  reason: EliminationReason,
): Promise<SpinParticipantRecord | null> {
  const eliminationOrder = (await countParticipantsByStatus(spinId, 'ELIMINATED')) + 1;

  try {
    return await SpinParticipantModel.findOneAndUpdate(
      { spinId, userId, status: 'ACTIVE' },
      {
        $set: {
          status: 'ELIMINATED',
          eliminationOrder,
          eliminatedAt: new Date(),
          eliminationReason: reason,
        },
      },
      { new: true },
    )
      .lean<SpinParticipantRecord>()
      .exec();
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      // Another writer took this order. The document was not modified.
      return null;
    }
    throw error;
  }
}

export type EliminationOutcome =
  | { kind: 'ELIMINATED'; participant: SpinParticipantRecord }
  | { kind: 'LAST_REMAINING'; userId: Types.ObjectId }
  | { kind: 'NONE_REMAINING' };

// Picks one active participant at random and eliminates them. The server chooses:
// no client input reaches this decision.
export async function eliminateNextParticipant(
  spinId: Types.ObjectId,
): Promise<EliminationOutcome> {
  // Bounded by the participant count; each attempt re-reads live state.
  for (let attempt = 0; attempt < 25; attempt += 1) {
    const activeUserIds = await findActiveUserIds(spinId);

    if (activeUserIds.length === 0) {
      return { kind: 'NONE_REMAINING' };
    }
    if (activeUserIds.length === 1) {
      return { kind: 'LAST_REMAINING', userId: activeUserIds[0] as Types.ObjectId };
    }

    const index = Math.floor(Math.random() * activeUserIds.length);
    const participant = await applyElimination(
      spinId,
      activeUserIds[index] as Types.ObjectId,
      'TIMER',
    );

    if (participant !== null) {
      return { kind: 'ELIMINATED', participant };
    }
  }

  throw new Error(`Could not eliminate a participant in spin ${spinId.toString()}`);
}

// A user who leaves mid-spin is eliminated immediately, with reason LEFT so recovery
// does not mistake it for a scheduled tick. Returns null when they were not active.
export async function eliminateParticipantForLeave(
  spinId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<SpinParticipantRecord | null> {
  for (let attempt = 0; attempt < 25; attempt += 1) {
    const participant = await applyElimination(spinId, userId, 'LEFT');
    if (participant !== null) {
      return participant;
    }

    // Null means either they were not active, or an order collision. Distinguish the
    // two so a genuine non-participant is not retried forever.
    const stillActive = await SpinParticipantModel.exists({ spinId, userId, status: 'ACTIVE' });
    if (stillActive === null) {
      return null;
    }
  }

  throw new Error(`Could not eliminate leaving user ${userId.toString()}`);
}

// Retained from Phase 2: exercised by existing tests and useful for direct control.
// The engine uses eliminateNextParticipant / eliminateParticipantForLeave instead.
export async function eliminateParticipant(
  spinId: Types.ObjectId,
  userId: Types.ObjectId,
  eliminationOrder: number,
  reason: EliminationReason = 'TIMER',
): Promise<SpinParticipantRecord | null> {
  try {
    return await SpinParticipantModel.findOneAndUpdate(
      { spinId, userId, status: 'ACTIVE' },
      {
        $set: {
          status: 'ELIMINATED',
          eliminationOrder,
          eliminatedAt: new Date(),
          eliminationReason: reason,
        },
      },
      { new: true },
    )
      .lean<SpinParticipantRecord>()
      .exec();
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      throw new DuplicateKeyError(
        duplicateKeyIndexName(error),
        `Elimination order ${String(eliminationOrder)} already used in spin ${spinId.toString()}`,
      );
    }
    throw error;
  }
}

export async function markWinner(
  spinId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<SpinParticipantRecord | null> {
  return SpinParticipantModel.findOneAndUpdate(
    { spinId, userId, status: 'ACTIVE' },
    { $set: { status: 'WINNER' } },
    { new: true },
  )
    .lean<SpinParticipantRecord>()
    .exec();
}
