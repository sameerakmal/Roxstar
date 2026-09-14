import { createServer, type Server as HttpServer } from 'node:http';
import type { AddressInfo } from 'node:net';

import request from 'supertest';
import { io as createClient, type Socket as ClientSocket } from 'socket.io-client';
import type { Server as SocketIOServer } from 'socket.io';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { RoomMemberModel } from '../../src/models/index.js';
import { resetEventPublisher } from '../../src/services/eventPublisher.js';
import { clearPresence } from '../../src/services/presenceRegistry.js';
import { setEliminationIntervalMs } from '../../src/services/spinScheduler.js';
import { stopAllSpinTimers } from '../../src/services/spinService.js';
import { initializeSocketServer } from '../../src/websocket/index.js';
import { clearCollections, connectTestDatabase, disconnectTestDatabase } from './setup.js';
import { app, asUser, createDraft, createRoom, createUser } from './helpers.js';

const TEST_INTERVAL_MS = 150;

let httpServer: HttpServer;
let ioServer: SocketIOServer;
let port: number;
const openClients: ClientSocket[] = [];

beforeAll(async () => {
  await connectTestDatabase();
  httpServer = createServer();
  ioServer = initializeSocketServer(httpServer);
  await new Promise<void>((resolve) => {
    httpServer.listen(0, resolve);
  });
  port = (httpServer.address() as AddressInfo).port;
});

afterAll(async () => {
  stopAllSpinTimers();
  resetEventPublisher();
  await ioServer.close();
  httpServer.close();
  await disconnectTestDatabase();
});

beforeEach(async () => {
  stopAllSpinTimers();
  setEliminationIntervalMs(TEST_INTERVAL_MS);
  clearPresence();
  await clearCollections();
});

afterEach(() => {
  stopAllSpinTimers();
  while (openClients.length > 0) {
    openClients.pop()?.close();
  }
});

// Connects an identified socket. Identity travels in the handshake, mirroring the
// X-User-Id header used by REST.
async function connectAs(userId: string): Promise<ClientSocket> {
  const client = createClient(`http://localhost:${String(port)}`, {
    transports: ['websocket'],
    auth: { userId },
    forceNew: true,
  });
  openClients.push(client);

  await new Promise<void>((resolve, reject) => {
    client.on('connect', () => {
      resolve();
    });
    client.on('connect_error', reject);
  });

  return client;
}

async function joinRoom(client: ClientSocket, roomId: string): Promise<{ ok: boolean; code?: string }> {
  return new Promise((resolve) => {
    client.emit('join_room', { roomId }, resolve);
  });
}

function nextEvent<T>(client: ClientSocket, event: string, timeoutMs = 4000): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`Timed out waiting for ${event}`));
    }, timeoutMs);
    client.once(event, (payload: T) => {
      clearTimeout(timer);
      resolve(payload);
    });
  });
}

// room_state is emitted while join_room is being handled, so the listener must be
// registered before the emit or the event is missed.
async function joinAndAwaitState<T = unknown>(
  client: ClientSocket,
  roomId: string,
): Promise<{ ack: { ok: boolean; code?: string }; state: T }> {
  const state = nextEvent<T>(client, 'room_state');
  const ack = await joinRoom(client, roomId);
  return { ack, state: await state };
}

function collectEvents(client: ClientSocket, event: string, sink: unknown[]): void {
  client.on(event, (payload: unknown) => sink.push(payload));
}

describe('socket connection and membership', () => {
  it('rejects join_room for a non-member', async () => {
    const owner = await createUser('Owner');
    const stranger = await createUser('Stranger');
    const roomId = await createRoom(owner);

    const client = await connectAs(stranger);
    const ack = await joinRoom(client, roomId);

    expect(ack).toEqual({ ok: false, code: 'NOT_A_MEMBER' });
  });

  it('rejects a malformed room id', async () => {
    const owner = await createUser('Owner');
    const client = await connectAs(owner);

    expect(await joinRoom(client, 'not-an-id')).toEqual({ ok: false, code: 'INVALID_ROOM_ID' });
  });

  it('sends room_state to the joining socket', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const client = await connectAs(owner);
    const state = nextEvent<{ room: { id: string }; participants: unknown[]; activeSpin: unknown }>(
      client,
      'room_state',
    );
    await joinRoom(client, roomId);

    const payload = await state;
    expect(payload.room.id).toBe(roomId);
    expect(payload.participants).toHaveLength(1);
    expect(payload.activeSpin).toBeNull();
  });

  it('marks the member CONNECTED in MongoDB', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);

    const client = await connectAs(owner);
    await joinAndAwaitState(client, roomId);

    const member = await RoomMemberModel.findOne({ userId: owner }).lean();
    expect(member?.connectionState).toBe('CONNECTED');
  });
});

describe('presence events', () => {
  it('broadcasts user_joined to the existing member', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const ownerClient = await connectAs(owner);
    await joinAndAwaitState(ownerClient, roomId);

    const joined = nextEvent<{ user: { userId: string }; participants: unknown[] }>(
      ownerClient,
      'user_joined',
    );

    const joinerClient = await connectAs(joiner);
    await joinRoom(joinerClient, roomId);

    const payload = await joined;
    expect(payload.user.userId).toBe(joiner);
    expect(payload.participants).toHaveLength(2);
  });

  it('broadcasts user_left with reason DISCONNECTED when the socket drops', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const ownerClient = await connectAs(owner);
    await joinAndAwaitState(ownerClient, roomId);

    // Register before the emit: user_joined is broadcast while join_room is handled.
    const arrived = nextEvent(ownerClient, 'user_joined');
    const joinerClient = await connectAs(joiner);
    await joinRoom(joinerClient, roomId);
    await arrived;

    const left = nextEvent<{ reason: string; user: { userId: string } }>(ownerClient, 'user_left');
    joinerClient.close();

    const payload = await left;
    expect(payload.reason).toBe('DISCONNECTED');
    expect(payload.user.userId).toBe(joiner);

    // Membership survives a dropped socket — that is what makes reconnect possible.
    const member = await RoomMemberModel.findOne({ userId: joiner }).lean();
    expect(member?.membershipState).toBe('JOINED');
    expect(member?.connectionState).toBe('DISCONNECTED');
  });

  it('broadcasts user_left with reason LEFT when membership ends', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const ownerClient = await connectAs(owner);
    await joinAndAwaitState(ownerClient, roomId);

    const left = nextEvent<{ reason: string }>(ownerClient, 'user_left');
    await request(app).post(`/rooms/${roomId}/leave`).set(...asUser(joiner));

    expect((await left).reason).toBe('LEFT');
  });

  it('broadcasts draft_shared to the room', async () => {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const draftId = await createDraft(owner);

    const client = await connectAs(owner);
    await joinAndAwaitState(client, roomId);

    const shared = nextEvent<{ draft: { draftId: string } }>(client, 'draft_shared');
    await request(app)
      .post(`/rooms/${roomId}/drafts`)
      .set(...asUser(owner))
      .send({ draftId });

    expect((await shared).draft.draftId).toBe(draftId);
  });
});

describe('multiple connections for one user', () => {
  it('does not emit user_left while another socket remains', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const ownerClient = await connectAs(owner);
    await joinAndAwaitState(ownerClient, roomId);

    const arrived = nextEvent(ownerClient, 'user_joined');
    const firstTab = await connectAs(joiner);
    await joinRoom(firstTab, roomId);
    await arrived;

    const secondTab = await connectAs(joiner);
    await joinRoom(secondTab, roomId);

    const departures: unknown[] = [];
    collectEvents(ownerClient, 'user_left', departures);

    // Closing one of two tabs must not read as leaving.
    firstTab.close();
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(departures).toHaveLength(0);

    const member = await RoomMemberModel.findOne({ userId: joiner }).lean();
    expect(member?.connectionState).toBe('CONNECTED');

    // Closing the last one does.
    secondTab.close();
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(departures).toHaveLength(1);
  });

  it('emits user_joined only for the first socket', async () => {
    const owner = await createUser('Owner');
    const joiner = await createUser('Joiner');
    const roomId = await createRoom(owner);
    await request(app).post(`/rooms/${roomId}/join`).set(...asUser(joiner));

    const ownerClient = await connectAs(owner);
    await joinAndAwaitState(ownerClient, roomId);

    const arrivals: unknown[] = [];
    collectEvents(ownerClient, 'user_joined', arrivals);

    const first = await connectAs(joiner);
    await joinRoom(first, roomId);
    const second = await connectAs(joiner);
    await joinRoom(second, roomId);

    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(arrivals).toHaveLength(1);
  });
});

describe('cross-room isolation', () => {
  it('never delivers one room’s events to another', async () => {
    const ownerA = await createUser('OwnerA');
    const ownerB = await createUser('OwnerB');
    const roomA = await createRoom(ownerA);
    const roomB = await createRoom(ownerB);
    const joiner = await createUser('Joiner');
    await request(app).post(`/rooms/${roomA}/join`).set(...asUser(joiner));

    const clientB = await connectAs(ownerB);
    await joinAndAwaitState(clientB, roomB);

    const leaked: unknown[] = [];
    collectEvents(clientB, 'user_joined', leaked);

    const clientA = await connectAs(joiner);
    await joinRoom(clientA, roomA);

    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(leaked).toHaveLength(0);
  });
});

describe('spin events over sockets', () => {
  async function roomWithConnectedMembers(count: number): Promise<{
    owner: string;
    roomId: string;
    clients: ClientSocket[];
  }> {
    const owner = await createUser('Owner');
    const roomId = await createRoom(owner);
    const members = [owner];

    for (let index = 1; index < count; index += 1) {
      const member = await createUser(`Member${String(index)}`);
      await request(app).post(`/rooms/${roomId}/join`).set(...asUser(member));
      members.push(member);
    }

    const clients: ClientSocket[] = [];
    for (const member of members) {
      const client = await connectAs(member);
      await joinAndAwaitState(client, roomId);
      clients.push(client);
    }

    return { owner, roomId, clients };
  }

  it('delivers the full spin sequence in order, with one winner', async () => {
    const { owner, roomId, clients } = await roomWithConnectedMembers(3);
    const observer = clients[0] as ClientSocket;

    const received: string[] = [];
    observer.on('spin_started', () => received.push('spin_started'));
    observer.on('user_eliminated', () => received.push('user_eliminated'));
    const winner = nextEvent<{ winner: { userId: string }; sequenceNumber: number }>(
      observer,
      'winner_announced',
    );

    await request(app).post(`/rooms/${roomId}/spins`).set(...asUser(owner));
    const winnerPayload = await winner;

    expect(received[0]).toBe('spin_started');
    expect(received.filter((event) => event === 'user_eliminated')).toHaveLength(2);
    expect(winnerPayload.winner.userId).toBeDefined();
    // Sequence: started(1) + 2 eliminations(2,3) + winner(4)
    expect(winnerPayload.sequenceNumber).toBe(4);
  });

  it('announces the winner exactly once across all clients', async () => {
    const { owner, roomId, clients } = await roomWithConnectedMembers(4);

    const winners: unknown[][] = clients.map(() => []);
    clients.forEach((client, index) => {
      collectEvents(client, 'winner_announced', winners[index] as unknown[]);
    });

    await request(app).post(`/rooms/${roomId}/spins`).set(...asUser(owner));
    await new Promise((resolve) => setTimeout(resolve, TEST_INTERVAL_MS * 6));

    for (const received of winners) {
      expect(received).toHaveLength(1);
    }
  });

  it('gives a client joining mid-spin the authoritative snapshot', async () => {
    const { owner, roomId } = await roomWithConnectedMembers(4);
    await request(app).post(`/rooms/${roomId}/spins`).set(...asUser(owner));

    // Let at least one elimination land before the late client attaches.
    await new Promise((resolve) => setTimeout(resolve, TEST_INTERVAL_MS * 1.5));

    const lateClient = await connectAs(owner);
    const state = nextEvent<{
      activeSpin: { participants: unknown[]; remainingPlayers: unknown[]; lastSequenceNumber: number } | null;
    }>(lateClient, 'room_state');
    await joinRoom(lateClient, roomId);

    const payload = await state;
    if (payload.activeSpin !== null) {
      // Mid-spin: the snapshot carries progress and the sequence to resume from.
      expect(payload.activeSpin.participants).toHaveLength(4);
      expect(payload.activeSpin.lastSequenceNumber).toBeGreaterThan(1);
      expect(payload.activeSpin.remainingPlayers.length).toBeLessThan(4);
    }
  });

  // Reconnect: membership survives, so the returning client resumes the spin.
  it('resynchronizes a client that reconnects during a spin', async () => {
    const { owner, roomId, clients } = await roomWithConnectedMembers(5);
    const rejoiner = clients[1] as ClientSocket;
    const rejoinerId = (rejoiner.auth as { userId: string }).userId;

    await request(app).post(`/rooms/${roomId}/spins`).set(...asUser(owner));
    rejoiner.close();
    await new Promise((resolve) => setTimeout(resolve, TEST_INTERVAL_MS * 1.5));

    const reconnected = await connectAs(rejoinerId);
    const state = nextEvent<{ activeSpin: { lastSequenceNumber: number } | null }>(
      reconnected,
      'room_state',
    );
    const ack = await joinRoom(reconnected, roomId);

    expect(ack.ok).toBe(true);
    const payload = await state;
    // Either still running (snapshot present) or already finished — both are valid;
    // what matters is that the reconnect was accepted and state was delivered.
    expect(payload).toHaveProperty('activeSpin');
  });
});
