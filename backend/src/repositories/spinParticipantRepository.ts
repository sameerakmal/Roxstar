import type { Types } from 'mongoose';

import {
  DuplicateKeyError,
  duplicateKeyIndexName,
  isDuplicateKeyError,
} from '../errors/RepositoryError.js';
import {
  SpinParticipantModel,
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

export async function activateParticipants(spinId: Types.ObjectId): Promise<number> {
  const result = await SpinParticipantModel.updateMany(
    { spinId, status: 'ELIGIBLE' },
    { $set: { status: 'ACTIVE' } },
  ).exec();
  return result.modifiedCount;
}

// Atomically claims one ACTIVE participant and marks them eliminated in a single
// document operation. Two concurrent elimination ticks cannot claim the same person:
// the second matches a document that is no longer ACTIVE. The unique partial index on
// eliminationOrder independently rejects a duplicated order.
export async function eliminateParticipant(
  spinId: Types.ObjectId,
  userId: Types.ObjectId,
  eliminationOrder: number,
): Promise<SpinParticipantRecord | null> {
  try {
    return await SpinParticipantModel.findOneAndUpdate(
      { spinId, userId, status: 'ACTIVE' },
      {
        $set: { status: 'ELIMINATED', eliminationOrder, eliminatedAt: new Date() },
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
