import { Types } from 'mongoose';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { ActiveSpinExistsError } from '../../src/errors/RepositoryError.js';
import {
  RoomDraftShareModel,
  RoomMemberModel,
  SpinEventModel,
  SpinModel,
  SpinParticipantModel,
} from '../../src/models/index.js';
import { spinRepository } from '../../src/repositories/index.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';

const oid = (): Types.ObjectId => new Types.ObjectId();
const DUPLICATE_KEY = 11000;

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(clearCollections);

describe('one active spin per room (unique partial index)', () => {
  it('blocks a second active spin in the same room', async () => {
    const roomId = oid();
    await SpinModel.create({ roomId, status: 'WAITING' });

    await expect(SpinModel.create({ roomId, status: 'RUNNING' })).rejects.toMatchObject({
      code: DUPLICATE_KEY,
    });
  });

  it('allows a new spin once the previous one is COMPLETED', async () => {
    const roomId = oid();
    const first = await SpinModel.create({ roomId, status: 'WAITING' });

    first.status = 'COMPLETED';
    first.winnerUserId = oid();
    first.completedAt = new Date();
    await first.save();

    const second = await SpinModel.create({ roomId, status: 'WAITING' });
    expect(second.status).toBe('WAITING');
  });

  it('keeps unlimited terminal spins as room history', async () => {
    const roomId = oid();
    await SpinModel.create({ roomId, status: 'ABORTED', completedAt: new Date() });
    await SpinModel.create({ roomId, status: 'ABORTED', completedAt: new Date() });
    await SpinModel.create({ roomId, status: 'COMPLETED', winnerUserId: oid() });

    expect(await SpinModel.countDocuments({ roomId })).toBe(3);
  });

  it('does not constrain other rooms', async () => {
    await SpinModel.create({ roomId: oid(), status: 'RUNNING' });
    const other = await SpinModel.create({ roomId: oid(), status: 'RUNNING' });

    expect(other.status).toBe('RUNNING');
  });

  // The requirement the whole constraint exists for: two start requests arriving at
  // the same instant. A read-then-write check would let both through, because both
  // would observe no active spin before either inserted. Here the database decides.
  it('lets exactly one of two concurrent creations win', async () => {
    const roomId = oid();

    const outcomes = await Promise.allSettled([
      spinRepository.createActiveSpin(roomId),
      spinRepository.createActiveSpin(roomId),
    ]);

    const fulfilled = outcomes.filter((outcome) => outcome.status === 'fulfilled');
    const rejected = outcomes.filter((outcome) => outcome.status === 'rejected');

    expect(fulfilled).toHaveLength(1);
    expect(rejected).toHaveLength(1);
    expect((rejected[0] as PromiseRejectedResult).reason).toBeInstanceOf(ActiveSpinExistsError);
    expect(await SpinModel.countDocuments({ roomId })).toBe(1);
  });

  it('lets exactly one win under a wider burst of concurrent creations', async () => {
    const roomId = oid();

    const outcomes = await Promise.allSettled(
      Array.from({ length: 8 }, () => spinRepository.createActiveSpin(roomId)),
    );

    expect(outcomes.filter((outcome) => outcome.status === 'fulfilled')).toHaveLength(1);
    expect(await SpinModel.countDocuments({ roomId })).toBe(1);
  });
});

describe('one active membership per user per room (unique partial index)', () => {
  it('blocks a duplicate active membership', async () => {
    const roomId = oid();
    const userId = oid();
    await RoomMemberModel.create({ roomId, userId });

    await expect(RoomMemberModel.create({ roomId, userId })).rejects.toMatchObject({
      code: DUPLICATE_KEY,
    });
  });

  it('allows rejoining after leaving, preserving history', async () => {
    const roomId = oid();
    const userId = oid();
    const first = await RoomMemberModel.create({ roomId, userId });

    first.membershipState = 'LEFT';
    first.leftAt = new Date();
    await first.save();

    await RoomMemberModel.create({ roomId, userId });

    expect(await RoomMemberModel.countDocuments({ roomId, userId })).toBe(2);
    expect(await RoomMemberModel.countDocuments({ roomId, userId, membershipState: 'JOINED' })).toBe(
      1,
    );
  });
});

describe('spin participant constraints', () => {
  it('blocks the same user appearing twice in one spin', async () => {
    const spinId = oid();
    const userId = oid();
    await SpinParticipantModel.create({ spinId, userId });

    await expect(SpinParticipantModel.create({ spinId, userId })).rejects.toMatchObject({
      code: DUPLICATE_KEY,
    });
  });

  it('blocks a duplicated elimination order within a spin', async () => {
    const spinId = oid();
    await SpinParticipantModel.create({
      spinId,
      userId: oid(),
      status: 'ELIMINATED',
      eliminationOrder: 1,
      eliminatedAt: new Date(),
      eliminationReason: 'TIMER',
    });

    await expect(
      SpinParticipantModel.create({
        spinId,
        userId: oid(),
        status: 'ELIMINATED',
        eliminationOrder: 1,
        eliminatedAt: new Date(),
        eliminationReason: 'TIMER',
      }),
    ).rejects.toMatchObject({ code: DUPLICATE_KEY });
  });

  // Regression guard for the $exists/$type distinction. With partialFilterExpression
  // { $exists: true } MongoDB indexes explicit nulls too, so a spin could hold only ONE
  // un-eliminated participant — breaking every spin at the second player.
  it('allows many participants to share a null elimination order', async () => {
    const spinId = oid();

    await SpinParticipantModel.create({ spinId, userId: oid(), status: 'ACTIVE' });
    await SpinParticipantModel.create({ spinId, userId: oid(), status: 'ACTIVE' });
    await SpinParticipantModel.create({ spinId, userId: oid(), status: 'ACTIVE' });

    expect(await SpinParticipantModel.countDocuments({ spinId, eliminationOrder: null })).toBe(3);
  });

  it('allows the same elimination order in a different spin', async () => {
    const eliminated = {
      status: 'ELIMINATED' as const,
      eliminationOrder: 1,
      eliminatedAt: new Date(),
      eliminationReason: 'TIMER' as const,
    };
    await SpinParticipantModel.create({ spinId: oid(), userId: oid(), ...eliminated });
    const other = await SpinParticipantModel.create({ spinId: oid(), userId: oid(), ...eliminated });

    expect(other.eliminationOrder).toBe(1);
  });
});

describe('spin event ordering', () => {
  it('blocks a duplicate sequence number within a spin', async () => {
    const spinId = oid();
    await SpinEventModel.create({
      spinId,
      sequenceNumber: 1,
      eventType: 'spin_started',
      payload: {},
    });

    await expect(
      SpinEventModel.create({
        spinId,
        sequenceNumber: 1,
        eventType: 'user_eliminated',
        payload: {},
      }),
    ).rejects.toMatchObject({ code: DUPLICATE_KEY });
  });

  it('allows the same sequence number in a different spin', async () => {
    await SpinEventModel.create({
      spinId: oid(),
      sequenceNumber: 1,
      eventType: 'spin_started',
      payload: {},
    });
    const other = await SpinEventModel.create({
      spinId: oid(),
      sequenceNumber: 1,
      eventType: 'spin_started',
      payload: {},
    });

    expect(other.sequenceNumber).toBe(1);
  });
});

describe('room draft share constraint', () => {
  it('blocks sharing the same draft into the same room twice', async () => {
    const roomId = oid();
    const draftId = oid();
    await RoomDraftShareModel.create({ roomId, draftId, sharedByUserId: oid() });

    await expect(
      RoomDraftShareModel.create({ roomId, draftId, sharedByUserId: oid() }),
    ).rejects.toMatchObject({ code: DUPLICATE_KEY });
  });
});
