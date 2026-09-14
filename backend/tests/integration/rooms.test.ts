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

  it('survives a burst of concurrent joins from the same user', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);

    const responses = await Promise.all(
      Array.from({ length: 6 }, () =>
        request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner)),
      ),
    );

    expect(responses.every((response) => response.status < 400)).toBe(true);
    expect(
      await RoomMemberModel.countDocuments({ roomId, userId: joiner, membershipState: 'JOINED' }),
    ).toBe(1);
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
