import { Types } from 'mongoose';
import request from 'supertest';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { SpinModel, SpinParticipantModel, SpinEventModel } from '../../src/models/index.js';
import { setEliminationIntervalMs } from '../../src/services/spinScheduler.js';
import { stopAllSpinTimers } from '../../src/services/spinService.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';
import { app, asUser, createRoom, createUser } from './helpers.js';

// A short injected interval keeps a full spin under a second instead of 5s per tick.
// Kept comfortably above MongoDB round-trip latency so ticks do not pile up on each
// other and make assertions about event ordering non-deterministic.
const TEST_INTERVAL_MS = 150;

type SpinBody = {
  spinId: string;
  status: string;
  participants: { userId: string; status: string; eliminationReason: string | null }[];
  remainingPlayers: { userId: string }[];
  winner: { userId: string } | null;
  events: { sequenceNumber: number; eventType: string }[];
  lastSequenceNumber: number;
};
type ErrorBody = { error: { code: string } };

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(async () => {
  stopAllSpinTimers();
  setEliminationIntervalMs(TEST_INTERVAL_MS);
  await clearCollections();
});
afterEach(() => {
  stopAllSpinTimers();
});

async function roomWithMembers(count: number): Promise<{ owner: string; roomId: string; members: string[] }> {
  const owner = await createUser('Owner');
  const roomId = await createRoom(owner);
  const members = [owner];

  for (let index = 1; index < count; index += 1) {
    const member = await createUser(`Member${String(index)}`);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(member));
    members.push(member);
  }

  return { owner, roomId, members };
}

const startSpin = (roomId: string, userId: string) =>
  request(app).post(`/rooms/${roomId}/spins`).set(...asUser(userId));

// Completion persists the spin document BEFORE appending winner_announced — without
// transactions those are two writes, so there is a brief window where the status is
// COMPLETED but its terminal event is not yet stored. Waiting on the status alone
// would race that window, so for COMPLETED we also wait for the event that the
// assertions actually depend on.
async function waitForSpinStatus(spinId: string, status: string, timeoutMs = 5000): Promise<void> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const spin = await SpinModel.findById(spinId).lean();

    if (spin?.status === status) {
      if (status !== 'COMPLETED') {
        return;
      }
      const terminal = await SpinEventModel.exists({ spinId, eventType: 'winner_announced' });
      if (terminal !== null) {
        return;
      }
    }

    await new Promise((resolve) => setTimeout(resolve, 15));
  }

  const spin = await SpinModel.findById(spinId).lean();
  throw new Error(`Spin did not reach ${status}; it is ${String(spin?.status)}`);
}

describe('POST /rooms/:roomId/spins — validation', () => {
  it('rejects a start with only 2 eligible players', async () => {
    const { owner, roomId } = await roomWithMembers(2);

    const response = await startSpin(roomId, owner);

    expect(response.status).toBe(409);
    expect((response.body as ErrorBody).error.code).toBe('INSUFFICIENT_PLAYERS');
  });

  it('accepts a start with exactly 3 eligible players', async () => {
    const { owner, roomId } = await roomWithMembers(3);

    const response = await startSpin(roomId, owner);

    expect(response.status).toBe(201);
    expect((response.body as SpinBody).participants).toHaveLength(3);
  });

  it('accepts exactly 20 and rejects 21', async () => {
    const twenty = await roomWithMembers(20);
    const ok = await startSpin(twenty.roomId, twenty.owner);
    expect(ok.status).toBe(201);

    const twentyOne = await roomWithMembers(21);
    const tooMany = await startSpin(twentyOne.roomId, twentyOne.owner);
    expect(tooMany.status).toBe(409);
    expect((tooMany.body as ErrorBody).error.code).toBe('TOO_MANY_PLAYERS');
  });

  // A rejected start must not leave a WAITING spin occupying the room's only slot.
  it('frees the room after a rejected start', async () => {
    const { owner, roomId } = await roomWithMembers(2);
    await startSpin(roomId, owner);

    const member = await createUser('Third');
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(member));

    expect((await startSpin(roomId, owner)).status).toBe(201);
  });

  it('rejects a start by a non-owner', async () => {
    const { roomId, members } = await roomWithMembers(3);

    const response = await startSpin(roomId, members[1] as string);

    expect(response.status).toBe(403);
    expect((response.body as ErrorBody).error.code).toBe('NOT_ROOM_OWNER');
  });

  it('404s for an unknown room', async () => {
    const user = await createUser('Ada');
    const response = await startSpin(new Types.ObjectId().toString(), user);

    expect(response.status).toBe(404);
  });
});

describe('POST /rooms/:roomId/spins — duplicate and concurrent starts', () => {
  it('rejects a second start while one is active', async () => {
    const { owner, roomId } = await roomWithMembers(4);
    expect((await startSpin(roomId, owner)).status).toBe(201);

    const second = await startSpin(roomId, owner);

    expect(second.status).toBe(409);
    expect((second.body as ErrorBody).error.code).toBe('ACTIVE_SPIN_EXISTS');
  });

  // The unique partial index, not a prior read, is what settles this.
  it('creates exactly one spin for two simultaneous starts', async () => {
    const { owner, roomId } = await roomWithMembers(4);

    const responses = await Promise.all([startSpin(roomId, owner), startSpin(roomId, owner)]);
    const created = responses.filter((response) => response.status === 201);
    const conflicts = responses.filter((response) => response.status === 409);

    expect(created).toHaveLength(1);
    expect(conflicts).toHaveLength(1);
    expect(await SpinModel.countDocuments({ roomId })).toBe(1);
  });

  it('creates exactly one spin under a burst of concurrent starts', async () => {
    const { owner, roomId } = await roomWithMembers(5);

    const responses = await Promise.all(
      Array.from({ length: 6 }, () => startSpin(roomId, owner)),
    );

    expect(responses.filter((response) => response.status === 201)).toHaveLength(1);
    expect(await SpinModel.countDocuments({ roomId })).toBe(1);
  });
});

describe('spin lifecycle', () => {
  it('persists participants and the spin_started event on start', async () => {
    const { owner, roomId } = await roomWithMembers(3);

    const response = await startSpin(roomId, owner);
    const body = response.body as SpinBody;

    expect(body.status).toBe('RUNNING');
    expect(await SpinParticipantModel.countDocuments({ spinId: body.spinId })).toBe(3);
    expect(body.events[0]?.eventType).toBe('spin_started');
    expect(body.events[0]?.sequenceNumber).toBe(1);
  });

  it('runs to completion with exactly one winner and n-1 eliminations', async () => {
    const { owner, roomId } = await roomWithMembers(4);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    await waitForSpinStatus(spinId, 'COMPLETED');

    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.winnerUserId).not.toBeNull();

    const winners = await SpinParticipantModel.countDocuments({ spinId, status: 'WINNER' });
    const eliminated = await SpinParticipantModel.countDocuments({ spinId, status: 'ELIMINATED' });
    expect(winners).toBe(1);
    expect(eliminated).toBe(3);
  });

  it('records events in order with exactly one winner_announced', async () => {
    const { owner, roomId } = await roomWithMembers(4);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    await waitForSpinStatus(spinId, 'COMPLETED');

    const events = await SpinEventModel.find({ spinId }).sort({ sequenceNumber: 1 }).lean();
    const types = events.map((event) => event.eventType);

    expect(types[0]).toBe('spin_started');
    expect(types.at(-1)).toBe('winner_announced');
    expect(types.filter((type) => type === 'winner_announced')).toHaveLength(1);
    expect(types.filter((type) => type === 'user_eliminated')).toHaveLength(3);
    // Sequence numbers strictly increase.
    expect(events.map((event) => event.sequenceNumber)).toEqual([1, 2, 3, 4, 5]);
  });

  it('marks every timer elimination with reason TIMER and a unique order', async () => {
    const { owner, roomId } = await roomWithMembers(4);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    await waitForSpinStatus(spinId, 'COMPLETED');

    const eliminated = await SpinParticipantModel.find({ spinId, status: 'ELIMINATED' }).lean();
    expect(eliminated.every((p) => p.eliminationReason === 'TIMER')).toBe(true);

    const orders = eliminated.map((p) => p.eliminationOrder).sort((a, b) => Number(a) - Number(b));
    expect(orders).toEqual([1, 2, 3]);
  });

  // A COMPLETED spin without a winner must be unreachable.
  it('never leaves a COMPLETED spin without a winner', async () => {
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    await waitForSpinStatus(spinId, 'COMPLETED');

    expect(await SpinModel.countDocuments({ status: 'COMPLETED', winnerUserId: null })).toBe(0);
  });

  it('frees the room for a new spin once the first completes', async () => {
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;
    await waitForSpinStatus(spinId, 'COMPLETED');

    expect((await startSpin(roomId, owner)).status).toBe(201);
  });
});

describe('participant departure', () => {
  it('eliminates a leaver immediately with reason LEFT', async () => {
    // A long interval so no timer tick interferes with the assertion.
    setEliminationIntervalMs(60_000);
    const { roomId, members } = await roomWithMembers(4);
    const { spinId } = (await startSpin(roomId, members[0] as string)).body as SpinBody;

    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(members[1] as string));

    const participant = await SpinParticipantModel.findOne({
      spinId,
      userId: members[1] as string,
    }).lean();

    expect(participant?.status).toBe('ELIMINATED');
    expect(participant?.eliminationReason).toBe('LEFT');
    expect(participant?.eliminationOrder).toBe(1);
  });

  it('continues running when the active count falls below three', async () => {
    setEliminationIntervalMs(60_000);
    const { roomId, members } = await roomWithMembers(4);
    const { spinId } = (await startSpin(roomId, members[0] as string)).body as SpinBody;

    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(members[1] as string));
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(members[2] as string));

    // Two remain: the spin keeps running, because 3-20 is a start-time rule only.
    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('RUNNING');
  });

  it('completes with the last participant as winner when others leave', async () => {
    setEliminationIntervalMs(60_000);
    const { roomId, members } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, members[0] as string)).body as SpinBody;

    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(members[1] as string));
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(members[2] as string));

    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('COMPLETED');
    expect(spin?.winnerUserId?.toString()).toBe(members[0]);
  });

  // Draining a room by sequential leaves cannot reach zero active participants: the
  // spin completes as soon as one remains, so the final leave is a no-op against an
  // already-terminal spin. The zero-participant ABORT path is reachable only through
  // recovery, and is covered in recovery.test.ts.
  it('completes at one remaining, leaving a later departure with nothing to do', async () => {
    setEliminationIntervalMs(60_000);
    const { roomId, members } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, members[0] as string)).body as SpinBody;

    for (const member of members) {
      await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(member));
    }

    // Members leave in order, so the last one is alone after the second departure and
    // wins there; the third leave then finds a terminal spin and does nothing.
    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('COMPLETED');
    expect(spin?.winnerUserId?.toString()).toBe(members[2]);

    // The winner keeps WINNER status even though they later left the room.
    const winner = await SpinParticipantModel.findOne({ spinId, userId: members[2] }).lean();
    expect(winner?.status).toBe('WINNER');
    expect(await SpinParticipantModel.countDocuments({ spinId, status: 'WINNER' })).toBe(1);
  });

  // The owner is not privileged once the spin is running.
  it('continues the spin when the owner leaves, eliminating them like anyone else', async () => {
    setEliminationIntervalMs(60_000);
    const { owner, roomId } = await roomWithMembers(4);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(owner));

    const spin = await SpinModel.findById(spinId).lean();
    expect(spin?.status).toBe('RUNNING');

    const ownerParticipant = await SpinParticipantModel.findOne({ spinId, userId: owner }).lean();
    expect(ownerParticipant?.status).toBe('ELIMINATED');
    expect(ownerParticipant?.eliminationReason).toBe('LEFT');
  });

  it('is unaffected by a member who left before the spin started', async () => {
    const { owner, roomId, members } = await roomWithMembers(4);
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(members[3] as string));

    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    expect(await SpinParticipantModel.countDocuments({ spinId })).toBe(3);
    expect(
      await SpinParticipantModel.countDocuments({ spinId, userId: members[3] as string }),
    ).toBe(0);
  });
});

describe('GET /spins/:spinId', () => {
  it('returns live state to a member', async () => {
    setEliminationIntervalMs(60_000);
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    const response = await request(app).get(`/spins/${spinId}`).set(...asUser(owner));
    const body = response.body as SpinBody;

    expect(response.status).toBe(200);
    expect(body.status).toBe('RUNNING');
    expect(body.remainingPlayers).toHaveLength(3);
    expect(body.lastSequenceNumber).toBe(1);
  });

  it('returns the final result and the event sequence once complete', async () => {
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;
    await waitForSpinStatus(spinId, 'COMPLETED');

    const body = (await request(app).get(`/spins/${spinId}`).set(...asUser(owner))).body as SpinBody;

    expect(body.status).toBe('COMPLETED');
    expect(body.winner).not.toBeNull();
    expect(body.events.at(-1)?.eventType).toBe('winner_announced');
  });

  it('403s for a non-member and 404s for an unknown spin', async () => {
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;
    const stranger = await createUser('Stranger');

    expect((await request(app).get(`/spins/${spinId}`).set(...asUser(stranger))).status).toBe(403);
    expect(
      (
        await request(app)
          .get(`/spins/${new Types.ObjectId().toString()}`)
          .set(...asUser(owner))
      ).status,
    ).toBe(404);
  });

  it('exposes no Mongoose internals', async () => {
    setEliminationIntervalMs(60_000);
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    const response = await request(app).get(`/spins/${spinId}`).set(...asUser(owner));
    const serialized = JSON.stringify(response.body);

    expect(serialized).not.toContain('_id');
    expect(serialized).not.toContain('__v');
  });
});

describe('room state exposes the active spin', () => {
  it('reports the running spin in GET /rooms/:roomId', async () => {
    setEliminationIntervalMs(60_000);
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;

    const state = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));
    const activeSpin = (state.body as { activeSpin: SpinBody | null }).activeSpin;

    expect(activeSpin?.spinId).toBe(spinId);
    expect(activeSpin?.status).toBe('RUNNING');
    expect(activeSpin?.remainingPlayers).toHaveLength(3);
  });

  it('reports null once the spin has completed', async () => {
    const { owner, roomId } = await roomWithMembers(3);
    const { spinId } = (await startSpin(roomId, owner)).body as SpinBody;
    await waitForSpinStatus(spinId, 'COMPLETED');

    const state = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));

    expect((state.body as { activeSpin: unknown }).activeSpin).toBeNull();
  });
});
