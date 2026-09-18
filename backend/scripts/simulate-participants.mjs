#!/usr/bin/env node
/**
 * RoxStar Realtime Participant Simulator
 * 
 * Simulates 2 additional participants (Bob and Charlie) joining a specified room,
 * connecting over Socket.IO, receiving real-time events (joins, shared drafts,
 * spin eliminations, winner announcements), and responding to keyboard commands.
 * 
 * Usage:
 *   node scripts/simulate-participants.mjs <ROOM_ID> [SERVER_URL]
 * 
 * Example:
 *   node scripts/simulate-participants.mjs 65f1234567890abcdef12345
 *   node scripts/simulate-participants.mjs 65f1234567890abcdef12345 https://roxstar-backend.politecliff-541c339a.centralindia.azurecontainerapps.io
 */

import { io } from 'socket.io-client';
import readline from 'readline';

let roomId = process.argv[2];
let countArg = process.argv[3];
let serverUrl = process.env.SERVER_URL ?? 'https://roxstar-backend.politecliff-541c339a.centralindia.azurecontainerapps.io';

// If argv[3] is a URL instead of a number:
let participantCount = 1;
if (countArg) {
  if (countArg.startsWith('http://') || countArg.startsWith('https://')) {
    serverUrl = countArg;
  } else {
    const parsed = parseInt(countArg, 10);
    if (!isNaN(parsed) && parsed > 0) participantCount = parsed;
    if (process.argv[4] && (process.argv[4].startsWith('http://') || process.argv[4].startsWith('https://'))) {
      serverUrl = process.argv[4];
    }
  }
}
serverUrl = serverUrl.replace(/\/$/, '');

if (!roomId || roomId.length !== 24) {
  console.error('\x1b[31mError: Please provide a valid 24-hex Room ID as the first argument.\x1b[0m');
  console.error('Usage: npm run simulate <ROOM_ID> [participantCount=1]');
  process.exit(1);
}

console.log('\x1b[36m====================================================\x1b[0m');
console.log(`\x1b[1m🚀 RoxStar Realtime Participant Simulator\x1b[0m`);
console.log(`   Target Server:       \x1b[33m${serverUrl}\x1b[0m`);
console.log(`   Target Room:         \x1b[32m${roomId}\x1b[0m`);
console.log(`   Participants to Add: \x1b[35m${participantCount}\x1b[0m`);
console.log('\x1b[36m====================================================\x1b[0m');

async function postJson(path, body, userId = null) {
  const headers = { 'Content-Type': 'application/json' };
  if (userId) headers['x-user-id'] = userId;
  const res = await fetch(`${serverUrl}${path}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data?.error?.message ?? `HTTP ${res.status}`);
  }
  return data;
}

async function createUser(displayName) {
  const user = await postJson('/users', { displayName });
  return user;
}

async function joinRoom(rId, userId) {
  return await postJson(`/rooms/${rId}/join`, {}, userId);
}

async function leaveRoom(rId, userId) {
  return await postJson(`/rooms/${rId}/leave`, {}, userId);
}

function connectSocket(user, label, color) {
  const socket = io(serverUrl, {
    auth: { userId: user.id },
    transports: ['websocket'],
    reconnection: true,
  });

  const log = (msg) => console.log(`${color}[${label}]\x1b[0m ${msg}`);

  socket.on('connect', () => {
    log(`\x1b[32mConnected via WebSocket (socketId: ${socket.id})\x1b[0m`);
    socket.emit('join_room', { roomId }, (ack) => {
      if (ack?.ok) {
        log(`\x1b[32mSuccessfully joined room channel\x1b[0m`);
      } else {
        log(`\x1b[31mFailed to join room channel: ${ack?.error}\x1b[0m`);
      }
    });
  });

  socket.on('room_state', (state) => {
    log(`📦 \x1b[34m[room_state]\x1b[0m Synced snapshot: ${state.participants.length} participants, activeSpin: ${state.activeSpin ? state.activeSpin.status : 'null'}`);
  });

  socket.on('user_joined', (evt) => {
    log(`👋 \x1b[32m[user_joined]\x1b[0m ${evt.user?.displayName ?? 'User'} joined (total ${evt.participants?.length ?? '?'})`);
  });

  socket.on('user_left', (evt) => {
    log(`🚪 \x1b[33m[user_left]\x1b[0m ${evt.user?.displayName ?? evt.userId} (${evt.reason}) (total ${evt.participants?.length ?? '?'})`);
  });

  socket.on('draft_shared', (evt) => {
    const d = evt.draft ?? evt;
    log(`🎵 \x1b[35m[draft_shared]\x1b[0m "${d.name}" (${d.effect}, ${d.durationMs}ms) shared into room!`);
  });

  socket.on('spin_started', (evt) => {
    log(`🎡 \x1b[33;1m[spin_started]\x1b[0m Spin ${evt.spinId.slice(-6)} started! ${evt.eligiblePlayers?.length ?? 0} contenders eligible!`);
  });

  socket.on('user_eliminated', (evt) => {
    log(`⚡ \x1b[31;1m[user_eliminated]\x1b[0m ${evt.eliminatedUser?.displayName ?? 'Player'} eliminated (#${evt.eliminationOrder}) — ${evt.remainingPlayers?.length} remaining!`);
  });

  socket.on('winner_announced', (evt) => {
    log(`👑 \x1b[32;1m[winner_announced]\x1b[0m CHAMPION: ${evt.winner?.displayName}! Spin COMPLETED!`);
  });

  socket.on('disconnect', (reason) => {
    log(`⚠️ Disconnected: ${reason}`);
  });

  return socket;
}

async function run() {
  try {
    const names = ['Charlie (Simulator)', 'Bob (Simulator)', 'Dave (Simulator)'];
    const users = [];
    const sockets = [];

    for (let i = 0; i < participantCount; i++) {
      const name = names[i % names.length];
      console.log(`\nCreating virtual participant ${i + 1}: ${name}...`);
      const user = await createUser(name);
      console.log(`Created ${name} with ID: ${user.id}`);
      users.push(user);

      console.log(`Joining room over REST API...`);
      await joinRoom(roomId, user.id);
      console.log(`${name} joined room via REST`);

      console.log(`Establishing real-time Socket.IO connection...`);
      const color = i === 0 ? '\x1b[35m' : '\x1b[36m';
      const socket = connectSocket(user, name.split(' ')[0], color);
      sockets.push(socket);
    }

    console.log(`\n\x1b[32mSuccessfully added ${users.length} simulated participant(s) to room!\x1b[0m`);
    console.log('They are now live on your Android Room Screen & Spin Wheel.');
    console.log('\n\x1b[1mKeyboard shortcuts:\x1b[0m');
    console.log('  \x1b[33m[l]\x1b[0m -> Trigger simulated participant leaving room (demonstrates user_left / elimination on leave)');
    console.log('  \x1b[33m[d]\x1b[0m -> Temporarily disconnect socket (demonstrates disconnect state)');
    console.log('  \x1b[33m[r]\x1b[0m -> Reconnect socket (demonstrates room_state sync)');
    console.log('  \x1b[31m[q]\x1b[0m -> Quit simulator\n');

    readline.emitKeypressEvents(process.stdin);
    if (process.stdin.isTTY) {
      process.stdin.setRawMode(true);
    }

    process.stdin.on('keypress', async (str, key) => {
      if (key.ctrl && key.name === 'c') process.exit(0);
      if (str === 'q') {
        console.log('Exiting simulator...');
        sockets.forEach((s) => s.disconnect());
        process.exit(0);
      }
      if (str === 'l') {
        const target = users[users.length - 1];
        console.log(`\n[Triggering ${target.displayName} LEAVE via REST]...`);
        try {
          await leaveRoom(roomId, target.id);
          console.log(`${target.displayName} left room.`);
        } catch (e) {
          console.error('Leave error:', e.message);
        }
      }
      if (str === 'd') {
        console.log('\n[Disconnecting socket]...');
        sockets[0]?.disconnect();
      }
      if (str === 'r') {
        console.log('\n[Reconnecting socket]...');
        sockets[0]?.connect();
      }
    });

  } catch (err) {
    console.error('\x1b[31mSetup failed:\x1b[0m', err.message);
    process.exit(1);
  }
}

run();
