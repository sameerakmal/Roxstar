import { Types } from 'mongoose';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { RoomMemberModel } from '../../src/models/index.js';
import { USER_ID_HEADER } from '../../src/middleware/currentUser.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';
import { app, asUser, createRoom, createUser } from './helpers.js';

type RoomStateBody = {
  room: { id: string; status: string; ownerUserId: string };
  participants: { userId: string; displayName: string; connectionState: string }[];
  sharedDrafts: unknown[];
  activeSpin: unknown;
};

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(clearCollections);

describe('identity (X-User-Id)', () => {
  it('rejects a request with no user header', async () => {
    const response = await request(app).post('/rooms');

    expect(response.status).toBe(401);
    expect((response.body as { error: { code: string } }).error.code).toBe('MISSING_USER_ID');
  });

  it('rejects a malformed user id', async () => {
    const response = await request(app).post('/rooms').set(USER_ID_HEADER, 'not-an-id');

    expect(response.status).toBe(400);
    expect((response.body as { error: { code: string } }).error.code).toBe('INVALID_USER_ID');
  });

  it('rejects a well-formed id that matches no user', async () => {
    const response = await request(app)
      .post('/rooms')
      .set(USER_ID_HEADER, new Types.ObjectId().toString());

    expect(response.status).toBe(401);
    expect((response.body as { error: { code: string } }).error.code).toBe('UNKNOWN_USER');
  });
});

describe('POST /users', () => {
  it('creates a user', async () => {
    const response = await request(app).post('/users').send({ displayName: 'Ada' });

    expect(response.status).toBe(201);
    expect((response.body as { displayName: string }).displayName).toBe('Ada');
  });

  it('rejects an empty display name with field details', async () => {
    const response = await request(app).post('/users').send({ displayName: '' });
    const body = response.body as { error: { code: string; details: { field: string }[] } };

    expect(response.status).toBe(400);
    expect(body.error.code).toBe('VALIDATION_ERROR');
    expect(body.error.details[0]?.field).toBe('displayName');
  });
});

describe('POST /rooms', () => {
  it('creates a room owned by and containing the caller', async () => {
    const userId = await createUser('Owner');
    const response = await request(app).post('/rooms').set(...asUser(userId));
    const body = response.body as RoomStateBody;

    expect(response.status).toBe(201);
    expect(body.room.status).toBe('ACTIVE');
    expect(body.room.ownerUserId).toBe(userId);
    // The creator is joined automatically, otherwise they could not read the room back.
    expect(body.participants).toHaveLength(1);
    expect(body.participants[0]?.userId).toBe(userId);
    expect(body.activeSpin).toBeNull();
  });
});

describe('POST /rooms/:roomId/join', () => {
  it('returns 201 on first join and lists both participants', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);

    const response = await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));
    const body = response.body as RoomStateBody;

    expect(response.status).toBe(201);
    expect(body.participants).toHaveLength(2);
    expect(body.participants.map((p) => p.displayName).sort()).toEqual(['Joiner', 'Owner']);
  });

  // Idempotency: a duplicate tap or a retried request is the same outcome the caller
  // asked for, so it succeeds with 200 rather than erroring.
  it('returns 200 on a repeated join without duplicating membership', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);

    expect((await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner))).status).toBe(
      201,
    );
    const second = await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    expect(second.status).toBe(200);
    expect((second.body as RoomStateBody).participants).toHaveLength(2);
    expect(
      await RoomMemberModel.countDocuments({ roomId, userId: joiner, membershipState: 'JOINED' }),
    ).toBe(1);
  });

  // The pre-check inside the service cannot settle this race; the unique partial index
  // does. Both callers must still see success, and only one membership may exist.
  it('creates exactly one membership for two simultaneous joins', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);

    const [first, second] = await Promise.all([
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner)),
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner)),
    ]);

    expect([first.status, second.status].every((status) => status === 200 || status === 201)).toBe(
      true,
    );
    expect(
      await RoomMemberModel.countDocuments({ roomId, userId: joiner, membershipState: 'JOINED' }),
    ).toBe(1);
  });

  // SP-15: Multiple distinct valid users attempt to join the same active room concurrently.
  // Proves membership consistency, capacity/constraints, zero duplicates, and state correctness.
  it('handles multiple distinct users joining concurrently, maintaining consistent membership and state (SP-15)', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    // Create 8 distinct valid users
    const joinerNames = ['Alice', 'Bob', 'Charlie', 'Diana', 'Evan', 'Fiona', 'George', 'Hannah'];
    const joinerIds = await Promise.all(joinerNames.map((name) => createUser(name)));

    // Concurrently issue join requests for all 8 distinct users
    const responses = await Promise.all(
      joinerIds.map((userId) => request(app).post(`/rooms/${roomId}/join`).set(...asUser(userId))),
    );

    // Every distinct user creates a new membership: all return 201
    expect(responses.map((r) => r.status)).toEqual(Array(8).fill(201));

    // MongoDB active membership count: 1 owner + 8 joiners = 9
    const totalActiveMembers = await RoomMemberModel.countDocuments({
      roomId,
      membershipState: 'JOINED',
    });
    expect(totalActiveMembers).toBe(9);

    // Verify each user has exactly 1 active membership and no phantom/duplicate records
    for (const userId of [owner, ...joinerIds]) {
      const activeCount = await RoomMemberModel.countDocuments({
        roomId,
        userId,
        membershipState: 'JOINED',
      });
      const totalCount = await RoomMemberModel.countDocuments({ roomId, userId });
      expect(activeCount).toBe(1);
      expect(totalCount).toBe(1);
    }

    // Verify authoritative room state via GET /rooms/:roomId contains all 9 participants
    const roomStateRes = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));
    expect(roomStateRes.status).toBe(200);
    const body = roomStateRes.body as RoomStateBody;

    expect(body.participants).toHaveLength(9);
    const participantIds = body.participants.map((p) => p.userId).sort();
    const expectedIds = [owner, ...joinerIds].sort();
    expect(participantIds).toEqual(expectedIds);

    // Verify display names are correctly resolved and uncorrupted
    const displayNames = body.participants.map((p) => p.displayName).sort();
    expect(displayNames).toEqual(['Alice', 'Bob', 'Charlie', 'Diana', 'Evan', 'Fiona', 'George', 'Hannah', 'Owner'].sort());
    expect(displayNames).not.toContain('Unknown user');

    // Verify all participants have valid state
    for (const p of body.participants) {
      expect(p.connectionState).toBe('DISCONNECTED');
    }
  });

  // SP-15: Interleaved concurrent joins with both distinct users and simultaneous duplicate requests
  it('handles concurrent joins with distinct users and racing duplicate requests without corruption (SP-15)', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const userA = await createUser('UserA');
    const userB = await createUser('UserB');
    const userC = await createUser('UserC');

    // 6 requests total: 2 concurrent requests per user
    const requests = [
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(userA)),
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(userA)),
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(userB)),
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(userB)),
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(userC)),
      request(app).post(`/rooms/${roomId}/join`).set(...asUser(userC)),
    ];

    const responses = await Promise.all(requests);

    // All requests succeed with 200 or 201
    expect(responses.every((r) => r.status === 200 || r.status === 201)).toBe(true);

    // Exactly 3 created (201) and 3 idempotent repeats (200)
    const createdCount = responses.filter((r) => r.status === 201).length;
    const repeatCount = responses.filter((r) => r.status === 200).length;
    expect(createdCount).toBe(3);
    expect(repeatCount).toBe(3);

    // Exactly 4 active members (1 owner + 3 users)
    expect(
      await RoomMemberModel.countDocuments({ roomId, membershipState: 'JOINED' }),
    ).toBe(4);

    // Zero duplicate memberships per user
    for (const uid of [userA, userB, userC]) {
      expect(
        await RoomMemberModel.countDocuments({ roomId, userId: uid, membershipState: 'JOINED' }),
      ).toBe(1);
    }
  });

  it('404s for a room that does not exist', async () => {
    const userId = await createUser('Ada');
    const response = await request(app)
      .post(`/rooms/${new Types.ObjectId().toString()}/join`)
      .set(...asUser(userId));

    expect(response.status).toBe(404);
    expect((response.body as { error: { code: string } }).error.code).toBe('ROOM_NOT_FOUND');
  });

  it('400s for a malformed room id', async () => {
    const userId = await createUser('Ada');
    const response = await request(app).post('/rooms/not-an-id/join').set(...asUser(userId));

    expect(response.status).toBe(400);
    expect((response.body as { error: { code: string } }).error.code).toBe('VALIDATION_ERROR');
  });
});

describe('POST /rooms/:roomId/leave', () => {
  it('removes the caller from the participant list', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const response = await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));
    const body = response.body as RoomStateBody;

    expect(response.status).toBe(200);
    expect(body.participants).toHaveLength(1);
    expect(body.participants[0]?.userId).toBe(owner);
  });

  it('403s when leaving a room you are not in', async () => {
    const owner = await createUser('Owner');
    const stranger = await createUser('Stranger');
    const roomId = await createRoom(owner);

    const response = await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(stranger));

    expect(response.status).toBe(403);
    expect((response.body as { error: { code: string } }).error.code).toBe('NOT_A_MEMBER');
  });

  it('403s on a repeated leave', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));

    const again = await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));

    expect(again.status).toBe(403);
  });

  it('allows rejoining after leaving and preserves membership history', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);

    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));
    const rejoin = await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    expect(rejoin.status).toBe(201);
    expect((rejoin.body as RoomStateBody).participants).toHaveLength(2);
    // Append-only membership: two rows for this user, exactly one of them active.
    expect(await RoomMemberModel.countDocuments({ roomId, userId: joiner })).toBe(2);
    expect(
      await RoomMemberModel.countDocuments({ roomId, userId: joiner, membershipState: 'JOINED' }),
    ).toBe(1);
  });

  // Phase 3 limitation, deliberately documented: the owner may leave, the room stays
  // ACTIVE and ownership does not transfer. Revisited in Phase 4 (SP-14).
  it('lets the owner leave without closing the room or transferring ownership', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const response = await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(owner));
    const body = response.body as RoomStateBody;

    expect(response.status).toBe(200);
    expect(body.room.status).toBe('ACTIVE');
    expect(body.room.ownerUserId).toBe(owner);
    expect(body.participants).toHaveLength(1);
    expect(body.participants[0]?.userId).toBe(joiner);

    // The remaining member can still read the room.
    const stillReadable = await request(app).get(`/rooms/${roomId}`).set(...asUser(joiner));
    expect(stillReadable.status).toBe(200);
  });
});

describe('GET /rooms/:roomId', () => {
  it('returns the authoritative snapshot to a member', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const response = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));
    const body = response.body as RoomStateBody;

    expect(response.status).toBe(200);
    expect(body.room.id).toBe(roomId);
    expect(body.participants[0]?.connectionState).toBe('DISCONNECTED');
    expect(body.sharedDrafts).toEqual([]);
    expect(body.activeSpin).toBeNull();
  });

  it('403s for a non-member', async () => {
    const owner = await createUser('Owner');
    const stranger = await createUser('Stranger');
    const roomId = await createRoom(owner);

    const response = await request(app).get(`/rooms/${roomId}`).set(...asUser(stranger));

    expect(response.status).toBe(403);
    expect((response.body as { error: { code: string } }).error.code).toBe('NOT_A_MEMBER');
  });

  it('403s for a member who has left', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));

    const response = await request(app).get(`/rooms/${roomId}`).set(...asUser(joiner));

    expect(response.status).toBe(403);
  });

  it('404s for an unknown room', async () => {
    const userId = await createUser('Ada');
    const response = await request(app)
      .get(`/rooms/${new Types.ObjectId().toString()}`)
      .set(...asUser(userId));

    expect(response.status).toBe(404);
  });

  // The snapshot is a wire contract, not a database dump.
  it('exposes no Mongoose internals', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const response = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));
    const serialized = JSON.stringify(response.body);

    expect(serialized).not.toContain('_id');
    expect(serialized).not.toContain('__v');
    expect(serialized).not.toContain('leftAt');
  });
});
