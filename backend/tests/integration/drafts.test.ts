import { Types } from 'mongoose';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { RoomDraftShareModel } from '../../src/models/index.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';
import { app, asUser, createDraft, createRoom, createUser } from './helpers.js';

type ErrorBody = { error: { code: string } };
type ShareBody = { draftId: string; name: string; effect: string; sharedByUserId: string };
type RoomStateBody = { sharedDrafts: { draftId: string; name: string }[] };

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(clearCollections);

describe('POST /drafts', () => {
  it('registers draft metadata for the caller', async () => {
    const userId = await createUser('Ada');
    const response = await request(app)
      .post('/drafts')
      .set(...asUser(userId))
      .send({ name: 'Take 1', durationMs: 4200, fileLocation: '/drafts/1.wav', effect: 'REVERB' });

    expect(response.status).toBe(201);
    expect((response.body as { ownerUserId: string; effect: string }).ownerUserId).toBe(userId);
    expect((response.body as { effect: string }).effect).toBe('REVERB');
  });

  it('defaults effect to NONE', async () => {
    const userId = await createUser('Ada');
    const response = await request(app)
      .post('/drafts')
      .set(...asUser(userId))
      .send({ name: 'Raw', durationMs: 1000, fileLocation: '/drafts/raw.wav' });

    expect((response.body as { effect: string }).effect).toBe('NONE');
  });

  it('rejects an unknown effect and a negative duration', async () => {
    const userId = await createUser('Ada');
    const base = { name: 'x', fileLocation: '/x.wav' };

    const badEffect = await request(app)
      .post('/drafts')
      .set(...asUser(userId))
      .send({ ...base, durationMs: 10, effect: 'AUTOTUNE' });
    const badDuration = await request(app)
      .post('/drafts')
      .set(...asUser(userId))
      .send({ ...base, durationMs: -5 });

    expect(badEffect.status).toBe(400);
    expect(badDuration.status).toBe(400);
    expect((badDuration.body as ErrorBody).error.code).toBe('VALIDATION_ERROR');
  });
});

describe('GET /drafts', () => {
  it('lists only the caller’s own drafts', async () => {
    const ada = await createUser('Ada');
    const bob = await createUser('Bob');
    await createDraft(ada, 'ada-1');
    await createDraft(ada, 'ada-2');
    await createDraft(bob, 'bob-1');

    const response = await request(app).get('/drafts').set(...asUser(ada));
    const body = response.body as { drafts: { name: string }[] };

    expect(response.status).toBe(200);
    expect(body.drafts).toHaveLength(2);
    expect(body.drafts.every((draft) => draft.name.startsWith('ada'))).toBe(true);
  });
});

describe('POST /rooms/:roomId/drafts', () => {
  it('shares a draft into a room and exposes it in room state', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const draftId = await createDraft(owner);

    const response = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId });

    expect(response.status).toBe(201);
    expect((response.body as ShareBody).draftId).toBe(draftId);
    expect((response.body as ShareBody).sharedByUserId).toBe(owner);

    const state = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));
    const body = state.body as RoomStateBody;
    expect(body.sharedDrafts).toHaveLength(1);
    expect(body.sharedDrafts[0]?.draftId).toBe(draftId);
  });

  it('returns 200 on a duplicate share without duplicating the row', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const draftId = await createDraft(owner);

    const first = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId });
    const second = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId });

    expect(first.status).toBe(201);
    expect(second.status).toBe(200);
    expect(await RoomDraftShareModel.countDocuments({ roomId, draftId })).toBe(1);
  });

  it('creates exactly one share for simultaneous duplicate requests', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const draftId = await createDraft(owner);

    const responses = await Promise.all(
      Array.from({ length: 5 }, () =>
        request(app)
          .post(`/rooms/${roomId}/drafts`)
          .set(...asUser(owner))
          .send({ draftId }),
      ),
    );

    expect(responses.every((response) => response.status < 400)).toBe(true);
    expect(await RoomDraftShareModel.countDocuments({ roomId, draftId })).toBe(1);
  });

  it('403s when sharing a draft you do not own', async () => {
    const owner = await createUser('Owner');
    const other = await createUser('Other');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(other));
    const ownersDraft = await createDraft(owner);

    const response = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(other))
      .send({ draftId: ownersDraft });

    expect(response.status).toBe(403);
    expect((response.body as ErrorBody).error.code).toBe('DRAFT_NOT_OWNED');
  });

  it('403s when sharing into a room you are not a member of', async () => {
    const owner = await createUser('Owner');
    const stranger = await createUser('Stranger');
    const roomId = await createRoom(owner);
    const draftId = await createDraft(stranger);

    const response = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(stranger))
      .send({ draftId });

    expect(response.status).toBe(403);
    expect((response.body as ErrorBody).error.code).toBe('NOT_A_MEMBER');
  });

  it('404s for a draft that does not exist', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const response = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId: new Types.ObjectId().toString() });

    expect(response.status).toBe(404);
    expect((response.body as ErrorBody).error.code).toBe('DRAFT_NOT_FOUND');
  });

  it('400s for a malformed draft id', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const response = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId: 'nope' });

    expect(response.status).toBe(400);
  });

  // A share records that the draft was shared; it is not retracted when the sharer
  // leaves, so the room's history stays intact.
  it('keeps a share visible after the sharer leaves the room', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));
    const draftId = await createDraft(joiner);

    await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(joiner))
      .send({ draftId });
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));

    const state = await request(app).get(`/rooms/${roomId}`).set(...asUser(owner));
    const body = state.body as RoomStateBody;

    expect(body.sharedDrafts).toHaveLength(1);
    expect(body.sharedDrafts[0]?.draftId).toBe(draftId);
  });

  it('exposes no Mongoose internals in the share response', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const draftId = await createDraft(owner);

    const response = await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId });
    const serialized = JSON.stringify(response.body);

    expect(serialized).not.toContain('_id');
    expect(serialized).not.toContain('__v');
  });
});
