import type { Types } from 'mongoose';

import {
  spinEventRepository,
  spinParticipantRepository,
  userRepository,
} from '../repositories/index.js';
import type { SpinParticipantRecord } from '../repositories/spinParticipantRepository.js';
import type { SpinRecord } from '../repositories/spinRepository.js';
import type { ActiveSpinDto, SpinEventDto, SpinPlayerDto, SpinStateDto } from './dto.js';

// Projection for everything spin-shaped. Kept out of the services so the wire format
// lives in one place and is reused by REST responses, room_state and broadcasts.

async function toPlayers(participants: SpinParticipantRecord[]): Promise<SpinPlayerDto[]> {
  if (participants.length === 0) {
    return [];
  }

  const users = await userRepository.findUsersByIds(participants.map((p) => p.userId));
  const displayNames = new Map(users.map((user) => [user._id.toString(), user.displayName]));

  return participants.map((participant) => ({
    userId: participant.userId.toString(),
    displayName: displayNames.get(participant.userId.toString()) ?? 'Unknown user',
    status: participant.status,
    eliminationOrder: participant.eliminationOrder ?? null,
    eliminationReason: participant.eliminationReason ?? null,
  }));
}

export async function buildPlayers(
  participants: SpinParticipantRecord[],
): Promise<SpinPlayerDto[]> {
  return toPlayers(participants);
}

function remainingOf(players: SpinPlayerDto[]): SpinPlayerDto[] {
  return players.filter((player) => player.status === 'ACTIVE' || player.status === 'WINNER');
}

function winnerOf(players: SpinPlayerDto[]): SpinPlayerDto | null {
  return players.find((player) => player.status === 'WINNER') ?? null;
}

export async function buildActiveSpinDto(spin: SpinRecord): Promise<ActiveSpinDto> {
  const participants = await spinParticipantRepository.findParticipants(spin._id);
  const players = await toPlayers(participants);
  const lastSequenceNumber = await spinEventRepository.findLastSequenceNumber(spin._id);

  return {
    spinId: spin._id.toString(),
    status: spin.status,
    startedAt: spin.startedAt ?? null,
    participants: players,
    remainingPlayers: remainingOf(players),
    winner: winnerOf(players),
    lastSequenceNumber,
  };
}

function toEventDto(event: {
  sequenceNumber: number;
  eventType: SpinEventDto['eventType'];
  payload: unknown;
  createdAt: Date;
}): SpinEventDto {
  return {
    sequenceNumber: event.sequenceNumber,
    eventType: event.eventType,
    payload: event.payload,
    createdAt: event.createdAt,
  };
}

// Full spin view: live state while RUNNING, final result plus the persisted event
// sequence once terminal.
export async function buildSpinStateDto(spin: SpinRecord): Promise<SpinStateDto> {
  const participants = await spinParticipantRepository.findParticipants(spin._id);
  const players = await toPlayers(participants);
  const events = await spinEventRepository.findEvents(spin._id);

  return {
    spinId: spin._id.toString(),
    roomId: spin.roomId.toString(),
    status: spin.status,
    startedAt: spin.startedAt ?? null,
    completedAt: spin.completedAt ?? null,
    startedByUserId: spin.startedByUserId?.toString() ?? null,
    abortReason: spin.abortReason ?? null,
    participants: players,
    remainingPlayers: remainingOf(players),
    winner: winnerOf(players),
    lastSequenceNumber: events.at(-1)?.sequenceNumber ?? 0,
    events: events.map(toEventDto),
  };
}

export async function buildPlayerDto(
  spinId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<SpinPlayerDto | null> {
  const participants = await spinParticipantRepository.findParticipants(spinId);
  const match = participants.find((p) => p.userId.toString() === userId.toString());
  if (match === undefined) {
    return null;
  }
  const [player] = await toPlayers([match]);
  return player ?? null;
}

export { remainingOf, winnerOf };
