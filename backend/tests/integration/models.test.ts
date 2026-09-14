import { Types } from 'mongoose';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  DraftModel,
  RoomDraftShareModel,
  RoomMemberModel,
  RoomModel,
  SpinEventModel,
  SpinModel,
  SpinParticipantModel,
  UserModel,
} from '../../src/models/index.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';

const oid = (): Types.ObjectId => new Types.ObjectId();

beforeAll(connectTestDatabase);
afterAll(disconnectTestDatabase);
beforeEach(clearCollections);

describe('User model', () => {
  it('requires a displayName', async () => {
    await expect(UserModel.create({})).rejects.toThrow(/displayName/);
  });

  it('trims the displayName and stamps timestamps', async () => {
    const user = await UserModel.create({ displayName: '  Ada  ' });

    expect(user.displayName).toBe('Ada');
    expect(user.createdAt).toBeInstanceOf(Date);
    expect(user.updatedAt).toBeInstanceOf(Date);
  });

  it('rejects a displayName longer than 50 characters', async () => {
    await expect(UserModel.create({ displayName: 'x'.repeat(51) })).rejects.toThrow(/displayName/);
  });
});

describe('Room model', () => {
  it('defaults status to ACTIVE', async () => {
    const room = await RoomModel.create({ ownerUserId: oid() });

    expect(room.status).toBe('ACTIVE');
  });

  it('rejects an unknown status', async () => {
    await expect(RoomModel.create({ ownerUserId: oid(), status: 'PAUSED' })).rejects.toThrow(
      /status/,
    );
  });

  it('requires an owner', async () => {
    await expect(RoomModel.create({})).rejects.toThrow(/ownerUserId/);
  });
});

describe('RoomMember model', () => {
  it('defaults to JOINED and DISCONNECTED', async () => {
    const member = await RoomMemberModel.create({ roomId: oid(), userId: oid() });

    expect(member.membershipState).toBe('JOINED');
    expect(member.connectionState).toBe('DISCONNECTED');
    expect(member.leftAt).toBeNull();
  });

  it('requires leftAt when membershipState is LEFT', async () => {
    await expect(
      RoomMemberModel.create({ roomId: oid(), userId: oid(), membershipState: 'LEFT' }),
    ).rejects.toThrow(/leftAt/);
  });

  it('rejects leftAt while still JOINED', async () => {
    await expect(
      RoomMemberModel.create({ roomId: oid(), userId: oid(), leftAt: new Date() }),
    ).rejects.toThrow(/leftAt/);
  });

  it('allows the two state axes to move independently', async () => {
    const member = await RoomMemberModel.create({
      roomId: oid(),
      userId: oid(),
      connectionState: 'CONNECTED',
    });

    member.connectionState = 'DISCONNECTED';
    await member.save();

    // A dropped socket must not look like a deliberate leave — this is what makes
    // reconnect recovery possible.
    expect(member.membershipState).toBe('JOINED');
    expect(member.connectionState).toBe('DISCONNECTED');
  });
});

describe('Draft model', () => {
  const baseDraft = {
    ownerUserId: oid(),
    name: 'Take 1',
    durationMs: 4200,
    fileLocation: '/drafts/take-1.wav',
  };

  it('defaults effect to NONE', async () => {
    const draft = await DraftModel.create(baseDraft);

    expect(draft.effect).toBe('NONE');
  });

  it('accepts the three assessment effects', async () => {
    for (const effect of ['ECHO', 'REVERB', 'PITCH_SHIFT'] as const) {
      const draft = await DraftModel.create({ ...baseDraft, effect });
      expect(draft.effect).toBe(effect);
    }
  });

  it('rejects an unknown effect', async () => {
    await expect(DraftModel.create({ ...baseDraft, effect: 'AUTOTUNE' })).rejects.toThrow(/effect/);
  });

  it('rejects a negative or fractional duration', async () => {
    await expect(DraftModel.create({ ...baseDraft, durationMs: -1 })).rejects.toThrow(/durationMs/);
    await expect(DraftModel.create({ ...baseDraft, durationMs: 1.5 })).rejects.toThrow(
      /durationMs/,
    );
  });
});

describe('Spin model', () => {
  it('defaults to WAITING with no winner', async () => {
    const spin = await SpinModel.create({ roomId: oid() });

    expect(spin.status).toBe('WAITING');
    expect(spin.winnerUserId).toBeNull();
    expect(spin.startedAt).toBeNull();
  });

  it('rejects a winner on a spin that is not COMPLETED', async () => {
    await expect(
      SpinModel.create({ roomId: oid(), status: 'RUNNING', winnerUserId: oid() }),
    ).rejects.toThrow(/winnerUserId/);
  });

  it('requires a winner when COMPLETED', async () => {
    await expect(SpinModel.create({ roomId: oid(), status: 'COMPLETED' })).rejects.toThrow(
      /winnerUserId/,
    );
  });

  it('accepts a COMPLETED spin that records its winner', async () => {
    const winnerUserId = oid();
    const spin = await SpinModel.create({
      roomId: oid(),
      status: 'COMPLETED',
      winnerUserId,
      completedAt: new Date(),
    });

    expect(spin.winnerUserId?.toString()).toBe(winnerUserId.toString());
  });
});

describe('SpinParticipant model', () => {
  it('defaults to ELIGIBLE with no elimination data', async () => {
    const participant = await SpinParticipantModel.create({ spinId: oid(), userId: oid() });

    expect(participant.status).toBe('ELIGIBLE');
    expect(participant.eliminationOrder).toBeNull();
    expect(participant.eliminatedAt).toBeNull();
  });

  it('requires order and timestamp when ELIMINATED', async () => {
    await expect(
      SpinParticipantModel.create({ spinId: oid(), userId: oid(), status: 'ELIMINATED' }),
    ).rejects.toThrow(/eliminationOrder/);
  });

  it('rejects elimination data on a participant still in the running', async () => {
    await expect(
      SpinParticipantModel.create({
        spinId: oid(),
        userId: oid(),
        status: 'ACTIVE',
        eliminationOrder: 2,
      }),
    ).rejects.toThrow(/eliminationOrder/);
  });
});

describe('SpinEvent model', () => {
  it('stores an ordered event with a payload', async () => {
    const event = await SpinEventModel.create({
      spinId: oid(),
      sequenceNumber: 1,
      eventType: 'spin_started',
      payload: { eligible: 3 },
    });

    expect(event.sequenceNumber).toBe(1);
    expect(event.eventType).toBe('spin_started');
  });

  it('accepts spin_aborted as a persisted terminal record', async () => {
    const event = await SpinEventModel.create({
      spinId: oid(),
      sequenceNumber: 2,
      eventType: 'spin_aborted',
      payload: { reason: 'INSUFFICIENT_PLAYERS' },
    });

    expect(event.eventType).toBe('spin_aborted');
  });

  it('rejects an unknown event type and a zero sequence number', async () => {
    await expect(
      SpinEventModel.create({ spinId: oid(), sequenceNumber: 1, eventType: 'nope', payload: {} }),
    ).rejects.toThrow(/eventType/);
    await expect(
      SpinEventModel.create({
        spinId: oid(),
        sequenceNumber: 0,
        eventType: 'spin_started',
        payload: {},
      }),
    ).rejects.toThrow(/sequenceNumber/);
  });
});

describe('RoomDraftShare model', () => {
  it('records who shared a draft into a room', async () => {
    const share = await RoomDraftShareModel.create({
      roomId: oid(),
      draftId: oid(),
      sharedByUserId: oid(),
    });

    expect(share.sharedAt).toBeInstanceOf(Date);
  });

  it('requires all three references', async () => {
    await expect(RoomDraftShareModel.create({ roomId: oid() })).rejects.toThrow(/draftId/);
  });
});
