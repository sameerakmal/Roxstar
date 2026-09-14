import { Types } from 'mongoose';
import request from 'supertest';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { SpinModel, SpinParticipantModel, SpinEventModel } from '../../src/models/index.js';
import { RoomMemberModel } from '../../src/models/index.js';
import { recoverSpins } from '../../src/services/spinRecoveryService.js';
import { setEliminationIntervalMs } from '../../src/services/spinScheduler.js';
import { stopAllSpinTimers } from '../../src/services/spinService.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';
import { app, asUser, createRoom, createUser } from './helpers.js';

const INTERVAL_MS = 5000;

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(async () => {
  stopAllSpinTimers();
  setEliminationIntervalMs(INTERVAL_MS);
  await clearCollections();
});
afterEach(() => {
  stopAllSpinTimers();
});

// Builds a RUNNING spin directly, standing in for state left behind by a process that
// died mid-spin. startedAt is backdated so a known number of ticks are already due.
async function buildRunningSpin(options: {
  participants: number;
  secondsAgo: number;
}): Promise<{ spinId: Types.ObjectId; roomId: Types.ObjectId; userIds: Types.ObjectId[] }> {
  const owner = await createUser('Owner');
  const roomId = await createRoom(owner);
  const userIds = [new Types.ObjectId(owner)];

  for (let index = 1; index < options.participants; index += 1) {
    const member = await createUser(`Member${String(index)}`);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(member));
    userIds.push(new Types.ObjectId(member));
  }

  const startedAt = new Date(Date.now() - options.secondsAgo * 1000);
  const spin = await SpinModel.create({
    roomId: new Types.ObjectId(roomId),
    status: 'RUNNING',
    startedAt,
    startedByUserId: new Types.ObjectId(owner),
  });

  await SpinParticipantModel.insertMany(
    userIds.map((userId) => ({ spinId: spin._id, userId, status: 'ACTIVE' })),
  );
  await SpinEventModel.create({
    spinId: spin._id,
    sequenceNumber: 1,
    eventType: 'spin_started',
    payload: {},
  });

  return { spinId: spin._id, roomId: new Types.ObjectId(roomId), userIds };
}

const activeCount = (spinId: Types.ObjectId): Promise<number> =>
  SpinParticipantModel.countDocuments({ spinId, status: 'ACTIVE' }).exec();

describe('recovery of a RUNNING spin', () => {
  it('catches up the eliminations that fell due while the process was down', async () => {
    // 5 players, 12s elapsed at a 5s cadence => 2 ticks owed.
    const { spinId } = await buildRunningSpin({ participants: 5, secondsAgo: 12 });

    await recoverSpins();

    expect(await activeCount(spinId)).toBe(3);
    const eliminated = await SpinParticipantModel.find({ spinId, status: 'ELIMINATED' }).lean();
    expect(eliminated).toHaveLength(2);
    expect(eliminated.every((p) => p.eliminationReason === 'TIMER')).toBe(true);
  });

  it('does nothing when no tick is yet due', async () => {
    const { spinId } = await buildRunningSpin({ participants: 4, secondsAgo: 2 });

    await recoverSpins();

    expect(await activeCount(spinId)).toBe(4);
  });

  // A long outage must not wipe out the room; it stops at exactly one winner.
  it('never eliminates past a single winner however long the outage', async () => {
    const { spinId } = await buildRunningSpin({ participants: 4, secondsAgo: 60 * 60 });

    await recoverSpins();

    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('COMPLETED');
    expect(spin?.winnerUserId).not.toBeNull();
    expect(await SpinParticipantModel.countDocuments({ spinId, status: 'WINNER' })).toBe(1);
    expect(await SpinParticipantModel.countDocuments({ spinId, status: 'ELIMINATED' })).toBe(3);
  });

  // Correction C: recovery must be safe to run repeatedly.
  it('is idempotent — a second run changes nothing', async () => {
    const { spinId } = await buildRunningSpin({ participants: 6, secondsAgo: 12 });

    await recoverSpins();
    const afterFirst = await SpinParticipantModel.find({ spinId }).sort({ userId: 1 }).lean();
    const eventsAfterFirst = await SpinEventModel.countDocuments({ spinId });

    await recoverSpins();
    const afterSecond = await SpinParticipantModel.find({ spinId }).sort({ userId: 1 }).lean();

    expect(afterSecond.map((p) => p.status)).toEqual(afterFirst.map((p) => p.status));
    expect(afterSecond.map((p) => p.eliminationOrder)).toEqual(
      afterFirst.map((p) => p.eliminationOrder),
    );
    expect(await SpinEventModel.countDocuments({ spinId })).toBe(eventsAfterFirst);
  });

  it('completes immediately when only one participant is still active', async () => {
    const { spinId, userIds } = await buildRunningSpin({ participants: 3, secondsAgo: 1 });
    await SpinParticipantModel.updateOne(
      { spinId, userId: userIds[1] },
      { $set: { status: 'ELIMINATED', eliminationOrder: 1, eliminatedAt: new Date(), eliminationReason: 'TIMER' } },
    );
    await SpinParticipantModel.updateOne(
      { spinId, userId: userIds[2] },
      { $set: { status: 'ELIMINATED', eliminationOrder: 2, eliminatedAt: new Date(), eliminationReason: 'LEFT' } },
    );

    await recoverSpins();

    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('COMPLETED');
    expect(spin?.winnerUserId?.toString()).toBe(userIds[0]?.toString());
  });

  // The zero-participant ABORT path, only reachable through recovery.
  it('aborts with no winner when no participant is active', async () => {
    const { spinId, userIds } = await buildRunningSpin({ participants: 3, secondsAgo: 1 });
    await SpinParticipantModel.updateMany(
      { spinId },
      {
        $set: {
          status: 'ELIMINATED',
          eliminatedAt: new Date(),
          eliminationReason: 'LEFT',
        },
      },
    );
    // Give each a distinct order to satisfy the unique index.
    for (const [index, userId] of userIds.entries()) {
      await SpinParticipantModel.updateOne({ spinId, userId }, { $set: { eliminationOrder: index + 1 } });
    }

    await recoverSpins();

    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('ABORTED');
    expect(spin?.winnerUserId).toBeNull();
    expect(spin?.abortReason).toBe('NO_PARTICIPANTS_REMAINING');
  });
});

// The reason eliminationReason exists: a LEFT elimination must not be mistaken for a
// scheduled tick, or recovery silently skips one and the spin ends early.
describe('recovery accounts for LEFT eliminations separately', () => {
  it('still owes the scheduled tick after a participant left before it', async () => {
    // 5 players, 6s elapsed => 1 tick due. One player left (not a tick).
    const { spinId, userIds } = await buildRunningSpin({ participants: 5, secondsAgo: 6 });
    await SpinParticipantModel.updateOne(
      { spinId, userId: userIds[4] },
      {
        $set: {
          status: 'ELIMINATED',
          eliminationOrder: 1,
          eliminatedAt: new Date(),
          eliminationReason: 'LEFT',
        },
      },
    );

    await recoverSpins();

    // The owed TIMER elimination still happens: 5 - 1 left - 1 timer = 3 active.
    expect(await activeCount(spinId)).toBe(3);
    expect(
      await SpinParticipantModel.countDocuments({ spinId, eliminationReason: 'TIMER' }),
    ).toBe(1);
    expect(
      await SpinParticipantModel.countDocuments({ spinId, eliminationReason: 'LEFT' }),
    ).toBe(1);
  });

  it('is idempotent for that same scenario', async () => {
    const { spinId, userIds } = await buildRunningSpin({ participants: 5, secondsAgo: 6 });
    await SpinParticipantModel.updateOne(
      { spinId, userId: userIds[4] },
      {
        $set: {
          status: 'ELIMINATED',
          eliminationOrder: 1,
          eliminatedAt: new Date(),
          eliminationReason: 'LEFT',
        },
      },
    );

    await recoverSpins();
    const first = await activeCount(spinId);
    const timerFirst = await SpinParticipantModel.countDocuments({
      spinId,
      eliminationReason: 'TIMER',
    });

    await recoverSpins();

    expect(await activeCount(spinId)).toBe(first);
    expect(await SpinParticipantModel.countDocuments({ spinId, eliminationReason: 'TIMER' })).toBe(
      timerFirst,
    );
  });

  it('does not double-count a LEFT elimination as a tick', async () => {
    // 5 players, 12s => 2 ticks due. One LEFT already recorded.
    const { spinId, userIds } = await buildRunningSpin({ participants: 5, secondsAgo: 12 });
    await SpinParticipantModel.updateOne(
      { spinId, userId: userIds[4] },
      {
        $set: {
          status: 'ELIMINATED',
          eliminationOrder: 1,
          eliminatedAt: new Date(),
          eliminationReason: 'LEFT',
        },
      },
    );

    await recoverSpins();

    // Both owed ticks run: 5 - 1 left - 2 timer = 2 active.
    expect(await activeCount(spinId)).toBe(2);
    expect(await SpinParticipantModel.countDocuments({ spinId, eliminationReason: 'TIMER' })).toBe(
      2,
    );
  });
});

describe('recovery of non-running state', () => {
  it('aborts an orphan WAITING spin so the room is not blocked forever', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const spin = await SpinModel.create({ roomId: new Types.ObjectId(roomId), status: 'WAITING' });

    const report = await recoverSpins();

    expect(report.abortedWaitingSpins).toBe(1);
    const recovered = await SpinModel.findById(spin._id).lean();
    expect(recovered?.status).toBe('ABORTED');
    expect(recovered?.abortReason).toBe('RECOVERED_INCOMPLETE_START');
  });

  it('lets the room start a new spin after the orphan is cleared', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    for (let index = 0; index < 2; index += 1) {
      const member = await createUser(`M${String(index)}`);
      await request(app).post(`/rooms/${roomId}/join`).set(...asUser(member));
    }
    await SpinModel.create({ roomId: new Types.ObjectId(roomId), status: 'WAITING' });

    await recoverSpins();

    const response = await request(app).post(`/rooms/${roomId}/spins`).set(...asUser(owner));
    expect(response.status).toBe(201);
  });

  // Every persisted CONNECTED flag is stale after a restart: no sockets exist.
  it('marks every member disconnected without touching membership', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    await RoomMemberModel.updateMany({}, { $set: { connectionState: 'CONNECTED' } });

    const report = await recoverSpins();

    expect(report.disconnectedMembers).toBeGreaterThan(0);
    const member = await RoomMemberModel.findOne({ roomId: new Types.ObjectId(roomId) }).lean();
    expect(member?.connectionState).toBe('DISCONNECTED');
    expect(member?.membershipState).toBe('JOINED');
  });

  it('ignores spins that already reached a terminal state', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const completed = await SpinModel.create({
      roomId: new Types.ObjectId(roomId),
      status: 'COMPLETED',
      winnerUserId: new Types.ObjectId(owner),
      completedAt: new Date(),
    });

    const report = await recoverSpins();

    expect(report.resumedSpins).toBe(0);
    expect((await SpinModel.findById(completed._id).lean())?.status).toBe('COMPLETED');
  });
});
