import { Types } from 'mongoose';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { ActiveSpinExistsError, DuplicateKeyError } from '../../src/errors/RepositoryError.js';
import {
  draftRepository,
  roomMemberRepository,
  roomRepository,
  spinEventRepository,
  spinParticipantRepository,
  spinRepository,
  userRepository,
} from '../../src/repositories/index.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';

const oid = (): Types.ObjectId => new Types.ObjectId();

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(clearCollections);

describe('userRepository', () => {
  it('creates and reads back a user', async () => {
    const created = await userRepository.createUser('Ada');
    const found = await userRepository.findUserById(created._id);

    expect(found?.displayName).toBe('Ada');
  });

  it('returns null for an unknown id', async () => {
    expect(await userRepository.findUserById(oid())).toBeNull();
  });
});

describe('roomRepository', () => {
  it('creates a room owned by a user and lists it', async () => {
    const owner = await userRepository.createUser('Owner');
    const room = await roomRepository.createRoom(owner._id);

    expect(room.status).toBe('ACTIVE');
    expect(await roomRepository.findRoomsByOwner(owner._id)).toHaveLength(1);
  });

  it('transitions status only from the expected state', async () => {
    const room = await roomRepository.createRoom(oid());

    const closed = await roomRepository.transitionRoomStatus(room._id, 'ACTIVE', 'CLOSED');
    expect(closed?.status).toBe('CLOSED');

    // Repeating the same transition matches nothing rather than reapplying it.
    const again = await roomRepository.transitionRoomStatus(room._id, 'ACTIVE', 'CLOSED');
    expect(again).toBeNull();
  });
});

describe('roomMemberRepository', () => {
  it('joins, lists and counts active members', async () => {
    const roomId = oid();
    await roomMemberRepository.joinRoom(roomId, oid());
    await roomMemberRepository.joinRoom(roomId, oid());

    expect(await roomMemberRepository.findActiveMembers(roomId)).toHaveLength(2);
    expect(await roomMemberRepository.countActiveMembers(roomId)).toBe(2);
  });

  it('translates a duplicate join into DuplicateKeyError', async () => {
    const roomId = oid();
    const userId = oid();
    await roomMemberRepository.joinRoom(roomId, userId);

    await expect(roomMemberRepository.joinRoom(roomId, userId)).rejects.toBeInstanceOf(
      DuplicateKeyError,
    );
  });

  it('makes leaving idempotent', async () => {
    const roomId = oid();
    const userId = oid();
    await roomMemberRepository.joinRoom(roomId, userId);

    const left = await roomMemberRepository.leaveRoom(roomId, userId);
    expect(left?.membershipState).toBe('LEFT');
    expect(left?.leftAt).toBeInstanceOf(Date);

    expect(await roomMemberRepository.leaveRoom(roomId, userId)).toBeNull();
    expect(await roomMemberRepository.countActiveMembers(roomId)).toBe(0);
  });

  // The behaviour reconnect depends on: a dropped socket must not end membership.
  it('changes connection state without ending membership', async () => {
    const roomId = oid();
    const userId = oid();
    await roomMemberRepository.joinRoom(roomId, userId, 'CONNECTED');

    const dropped = await roomMemberRepository.setConnectionState(roomId, userId, 'DISCONNECTED');
    expect(dropped?.connectionState).toBe('DISCONNECTED');
    expect(dropped?.membershipState).toBe('JOINED');

    const restored = await roomMemberRepository.setConnectionState(roomId, userId, 'CONNECTED');
    expect(restored?.connectionState).toBe('CONNECTED');
    expect(await roomMemberRepository.findActiveMembership(roomId, userId)).not.toBeNull();
  });

  it('keeps join/leave history after a rejoin', async () => {
    const roomId = oid();
    const userId = oid();
    await roomMemberRepository.joinRoom(roomId, userId);
    await roomMemberRepository.leaveRoom(roomId, userId);
    await roomMemberRepository.joinRoom(roomId, userId);

    expect(await roomMemberRepository.findMembershipHistory(roomId)).toHaveLength(2);
    expect(await roomMemberRepository.countActiveMembers(roomId)).toBe(1);
  });
});

describe('draftRepository', () => {
  const draftInput = (ownerUserId: Types.ObjectId): Parameters<typeof draftRepository.createDraft>[0] => ({
    ownerUserId,
    name: 'Take 1',
    durationMs: 3000,
    fileLocation: '/drafts/take-1.wav',
    effect: 'ECHO',
  });

  it('creates, lists and deletes a draft', async () => {
    const owner = await userRepository.createUser('Ada');
    const draft = await draftRepository.createDraft(draftInput(owner._id));

    expect(await draftRepository.findDraftsByOwner(owner._id)).toHaveLength(1);
    expect(await draftRepository.deleteDraft(draft._id)).toBe(true);
    expect(await draftRepository.findDraftById(draft._id)).toBeNull();
  });

  it('shares a draft with a room once', async () => {
    const owner = await userRepository.createUser('Ada');
    const draft = await draftRepository.createDraft(draftInput(owner._id));
    const roomId = oid();

    await draftRepository.shareDraftWithRoom(roomId, draft._id, owner._id);

    expect(await draftRepository.findSharedDrafts(roomId)).toHaveLength(1);
    await expect(
      draftRepository.shareDraftWithRoom(roomId, draft._id, owner._id),
    ).rejects.toBeInstanceOf(DuplicateKeyError);
  });
});

describe('spinRepository', () => {
  it('creates an active spin and finds it', async () => {
    const roomId = oid();
    const spin = await spinRepository.createActiveSpin(roomId);

    expect(spin.status).toBe('WAITING');
    expect((await spinRepository.findActiveSpin(roomId))?._id.toString()).toBe(spin._id.toString());
  });

  it('rejects a second active spin with ActiveSpinExistsError', async () => {
    const roomId = oid();
    await spinRepository.createActiveSpin(roomId);

    await expect(spinRepository.createActiveSpin(roomId)).rejects.toBeInstanceOf(
      ActiveSpinExistsError,
    );
  });

  it('enforces the lifecycle through conditional transitions', async () => {
    const roomId = oid();
    const spin = await spinRepository.createActiveSpin(roomId);

    const running = await spinRepository.transitionSpinStatus(spin._id, 'WAITING', 'RUNNING', {
      startedAt: new Date(),
    });
    expect(running?.status).toBe('RUNNING');

    // Illegal transition: the spin is no longer WAITING, so nothing is written.
    expect(await spinRepository.transitionSpinStatus(spin._id, 'WAITING', 'ABORTED')).toBeNull();

    const winnerUserId = oid();
    const completed = await spinRepository.transitionSpinStatus(
      spin._id,
      'RUNNING',
      'COMPLETED',
      { completedAt: new Date(), winnerUserId },
    );
    expect(completed?.status).toBe('COMPLETED');
    expect(completed?.winnerUserId?.toString()).toBe(winnerUserId.toString());

    // Terminal states are final.
    expect(await spinRepository.transitionSpinStatus(spin._id, 'RUNNING', 'ABORTED')).toBeNull();

    // With the spin finished, the room is free to start another.
    expect(await spinRepository.findActiveSpin(roomId)).toBeNull();
    await expect(spinRepository.createActiveSpin(roomId)).resolves.toBeDefined();
  });

  it('finds spins still active after a restart', async () => {
    await spinRepository.createActiveSpin(oid());
    await spinRepository.createActiveSpin(oid());

    expect(await spinRepository.findAllActiveSpins()).toHaveLength(2);
  });
});

describe('spinParticipantRepository', () => {
  it('snapshots participants and activates them', async () => {
    const spinId = oid();
    const userIds = [oid(), oid(), oid()];

    await spinParticipantRepository.addParticipants(spinId, userIds);
    expect(await spinParticipantRepository.countParticipantsByStatus(spinId, 'ELIGIBLE')).toBe(3);

    expect(await spinParticipantRepository.activateParticipants(spinId)).toBe(3);
    expect(await spinParticipantRepository.countParticipantsByStatus(spinId, 'ACTIVE')).toBe(3);
  });

  it('claims a participant exactly once when eliminating', async () => {
    const spinId = oid();
    const userId = oid();
    await spinParticipantRepository.addParticipants(spinId, [userId, oid()]);
    await spinParticipantRepository.activateParticipants(spinId);

    const eliminated = await spinParticipantRepository.eliminateParticipant(spinId, userId, 1);
    expect(eliminated?.status).toBe('ELIMINATED');
    expect(eliminated?.eliminationOrder).toBe(1);

    // A repeated tick for the same user matches nothing: they are no longer ACTIVE.
    expect(await spinParticipantRepository.eliminateParticipant(spinId, userId, 2)).toBeNull();
  });

  it('runs a full elimination sequence down to one winner', async () => {
    const spinId = oid();
    const userIds = [oid(), oid(), oid()];
    await spinParticipantRepository.addParticipants(spinId, userIds);
    await spinParticipantRepository.activateParticipants(spinId);

    await spinParticipantRepository.eliminateParticipant(spinId, userIds[0] as Types.ObjectId, 1);
    await spinParticipantRepository.eliminateParticipant(spinId, userIds[1] as Types.ObjectId, 2);

    const remaining = await spinParticipantRepository.findParticipantsByStatus(spinId, 'ACTIVE');
    expect(remaining).toHaveLength(1);

    const winner = await spinParticipantRepository.markWinner(
      spinId,
      remaining[0]?.userId as Types.ObjectId,
    );
    expect(winner?.status).toBe('WINNER');
    expect(await spinParticipantRepository.countParticipantsByStatus(spinId, 'ELIMINATED')).toBe(2);
  });

  it('rejects duplicate participants', async () => {
    const spinId = oid();
    const userId = oid();
    await spinParticipantRepository.addParticipants(spinId, [userId]);

    await expect(
      spinParticipantRepository.addParticipants(spinId, [userId]),
    ).rejects.toBeInstanceOf(DuplicateKeyError);
  });
});

describe('spinEventRepository', () => {
  it('appends events and reads them back in order', async () => {
    const spinId = oid();
    await spinEventRepository.appendEvent(spinId, 1, 'spin_started', { eligible: 3 });
    await spinEventRepository.appendEvent(spinId, 2, 'user_eliminated', { order: 1 });
    await spinEventRepository.appendEvent(spinId, 3, 'winner_announced', { winner: 'x' });

    const events = await spinEventRepository.findEvents(spinId);
    expect(events.map((event) => event.eventType)).toEqual([
      'spin_started',
      'user_eliminated',
      'winner_announced',
    ]);
  });

  it('makes a replayed event a duplicate rather than a second copy', async () => {
    const spinId = oid();
    await spinEventRepository.appendEvent(spinId, 1, 'spin_started', {});

    await expect(
      spinEventRepository.appendEvent(spinId, 1, 'spin_started', {}),
    ).rejects.toBeInstanceOf(DuplicateKeyError);
    expect(await spinEventRepository.findEvents(spinId)).toHaveLength(1);
  });

  // What a reconnecting client needs: the sequence it already holds, then only the rest.
  it('replays only the events after a given sequence number', async () => {
    const spinId = oid();
    await spinEventRepository.appendEvent(spinId, 1, 'spin_started', {});
    await spinEventRepository.appendEvent(spinId, 2, 'user_eliminated', {});
    await spinEventRepository.appendEvent(spinId, 3, 'user_eliminated', {});

    expect(await spinEventRepository.findLastSequenceNumber(spinId)).toBe(3);

    const missed = await spinEventRepository.findEventsSince(spinId, 1);
    expect(missed.map((event) => event.sequenceNumber)).toEqual([2, 3]);
  });

  it('reports sequence zero for a spin with no events', async () => {
    expect(await spinEventRepository.findLastSequenceNumber(oid())).toBe(0);
  });
});
