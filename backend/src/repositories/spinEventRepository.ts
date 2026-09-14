import type { Types } from 'mongoose';

import {
  DuplicateKeyError,
  duplicateKeyIndexName,
  isDuplicateKeyError,
} from '../errors/RepositoryError.js';
import { SpinEventModel, type SpinEvent, type SpinEventType } from '../models/index.js';

export type SpinEventRecord = SpinEvent & { _id: Types.ObjectId };

// Appends one event at an explicit sequence number. The unique (spinId, sequenceNumber)
// index makes this safely retryable: replaying the same event raises DuplicateKeyError
// instead of writing a second copy, which is what keeps persist-then-broadcast idempotent.
export async function appendEvent(
  spinId: Types.ObjectId,
  sequenceNumber: number,
  eventType: SpinEventType,
  payload: Record<string, unknown>,
): Promise<SpinEventRecord> {
  try {
    const created = await SpinEventModel.create({ spinId, sequenceNumber, eventType, payload });
    return created.toObject();
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      throw new DuplicateKeyError(
        duplicateKeyIndexName(error),
        `Sequence ${String(sequenceNumber)} already recorded for spin ${spinId.toString()}`,
      );
    }
    throw error;
  }
}

export async function findEvents(spinId: Types.ObjectId): Promise<SpinEventRecord[]> {
  return SpinEventModel.find({ spinId })
    .sort({ sequenceNumber: 1 })
    .lean<SpinEventRecord[]>()
    .exec();
}

// Replay for a reconnecting client: everything after the sequence number it already holds.
export async function findEventsSince(
  spinId: Types.ObjectId,
  afterSequenceNumber: number,
): Promise<SpinEventRecord[]> {
  return SpinEventModel.find({ spinId, sequenceNumber: { $gt: afterSequenceNumber } })
    .sort({ sequenceNumber: 1 })
    .lean<SpinEventRecord[]>()
    .exec();
}

// The snapshot's lastSequenceNumber, so a client knows where its replay should start.
export async function findLastSequenceNumber(spinId: Types.ObjectId): Promise<number> {
  const latest = await SpinEventModel.findOne({ spinId })
    .sort({ sequenceNumber: -1 })
    .select('sequenceNumber')
    .lean<{ sequenceNumber: number }>()
    .exec();
  return latest?.sequenceNumber ?? 0;
}

// Appends the next event without a caller-supplied sequence number. The sequence is
// derived from the log itself and the unique (spinId, sequenceNumber) index settles
// races: a collision is retried against fresh state rather than overwriting.
//
// Sequence numbers are unique and monotonically increasing, but NOT guaranteed
// gapless: without transactions a crash can leave a hole. room_state is authoritative
// and clients repair from the snapshot rather than assuming contiguity.
export async function appendNextEvent(
  spinId: Types.ObjectId,
  eventType: SpinEventType,
  payload: Record<string, unknown>,
): Promise<SpinEventRecord> {
  for (let attempt = 0; attempt < 25; attempt += 1) {
    const next = (await findLastSequenceNumber(spinId)) + 1;
    try {
      const created = await SpinEventModel.create({
        spinId,
        sequenceNumber: next,
        eventType,
        payload,
      });
      return created.toObject();
    } catch (error: unknown) {
      if (!isDuplicateKeyError(error)) {
        throw error;
      }
    }
  }

  throw new Error(`Could not append event for spin ${spinId.toString()}`);
}
