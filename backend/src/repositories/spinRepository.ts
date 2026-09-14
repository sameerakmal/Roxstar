import type { Types } from 'mongoose';

import { ActiveSpinExistsError, isDuplicateKeyError } from '../errors/RepositoryError.js';
import { ACTIVE_SPIN_STATUSES, SpinModel, type Spin, type SpinStatus } from '../models/index.js';

export type SpinRecord = Spin & { _id: Types.ObjectId };

// Creates the room's active spin.
//
// There is deliberately NO "find an existing active spin, then insert if absent"
// sequence here. That pattern has a window between the read and the write in which
// two concurrent requests both observe no active spin and both insert. Instead the
// insert is issued unconditionally and the unique partial index decides the winner
// atomically; the loser's duplicate-key error becomes ActiveSpinExistsError.
export async function createActiveSpin(
  roomId: Types.ObjectId,
  startedByUserId?: Types.ObjectId,
): Promise<SpinRecord> {
  try {
    const created = await SpinModel.create({ roomId, startedByUserId: startedByUserId ?? null });
    return created.toObject();
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      throw new ActiveSpinExistsError(roomId.toString());
    }
    throw error;
  }
}

export async function findActiveSpin(roomId: Types.ObjectId): Promise<SpinRecord | null> {
  return SpinModel.findOne({ roomId, status: { $in: ACTIVE_SPIN_STATUSES } })
    .lean<SpinRecord>()
    .exec();
}

export async function findSpinById(spinId: Types.ObjectId): Promise<SpinRecord | null> {
  return SpinModel.findById(spinId).lean<SpinRecord>().exec();
}

export async function findSpinsByRoom(roomId: Types.ObjectId): Promise<SpinRecord[]> {
  return SpinModel.find({ roomId })
    .sort({ createdAt: -1 })
    .lean<SpinRecord[]>()
    .exec();
}

// Every lifecycle change is a conditional update keyed on the expected current status.
// An illegal transition matches zero documents and writes nothing, so the state machine
// is enforced by the database rather than by whoever remembered to check first.
export async function transitionSpinStatus(
  spinId: Types.ObjectId,
  from: SpinStatus,
  to: SpinStatus,
  fields: { startedAt?: Date; completedAt?: Date; winnerUserId?: Types.ObjectId } = {},
): Promise<SpinRecord | null> {
  return SpinModel.findOneAndUpdate(
    { _id: spinId, status: from },
    { $set: { status: to, ...fields } },
    { new: true, runValidators: false },
  )
    .lean<SpinRecord>()
    .exec();
}

// Recovery entry point after a restart: any spin left mid-flight when the process died.
export async function findAllActiveSpins(): Promise<SpinRecord[]> {
  return SpinModel.find({ status: { $in: ACTIVE_SPIN_STATUSES } })
    .lean<SpinRecord[]>()
    .exec();
}

// Completion is one compare-and-swap that sets status, completedAt and the winner
// together, so a COMPLETED spin without a winner is unreachable. Only one caller can
// win this update however many timers or requests race for it — which is what makes
// the winner announcement exactly-once.
export async function completeSpin(
  spinId: Types.ObjectId,
  winnerUserId: Types.ObjectId,
): Promise<SpinRecord | null> {
  return SpinModel.findOneAndUpdate(
    { _id: spinId, status: 'RUNNING' },
    { $set: { status: 'COMPLETED', completedAt: new Date(), winnerUserId } },
    { new: true },
  )
    .lean<SpinRecord>()
    .exec();
}

// Ends a spin that cannot produce a winner, freeing the room for a new one.
export async function abortSpin(
  spinId: Types.ObjectId,
  reason: string,
): Promise<SpinRecord | null> {
  return SpinModel.findOneAndUpdate(
    { _id: spinId, status: { $in: ACTIVE_SPIN_STATUSES } },
    { $set: { status: 'ABORTED', completedAt: new Date(), abortReason: reason } },
    { new: true },
  )
    .lean<SpinRecord>()
    .exec();
}
