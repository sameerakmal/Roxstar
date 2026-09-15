#!/usr/bin/env node
// Post-deployment smoke test.
//
// Verifies the three things a deployment can break that the unit and integration
// suites cannot: that the process is live, that it can reach its database, and that
// WebSocket upgrades survive the cloud ingress. The deploy workflow rolls back when
// this fails, so it is the release gate rather than a diagnostic.
//
// Usage:
//   node scripts/smoke.mjs https://roxstar-backend-xxxx.a.run.app
//   SMOKE_SKIP_WRITE=1 node scripts/smoke.mjs <url>   # read-only checks only

import { io } from 'socket.io-client';

const baseUrl = (process.argv[2] ?? process.env.SMOKE_URL ?? 'http://localhost:3000').replace(
  /\/$/,
  '',
);
const skipWrite = process.env.SMOKE_SKIP_WRITE === '1';
const TIMEOUT_MS = Number(process.env.SMOKE_TIMEOUT_MS ?? 15000);

let failures = 0;

function report(name, ok, detail = '') {
  const status = ok ? 'PASS' : 'FAIL';
  console.log(`  [${status}] ${name}${detail === '' ? '' : ` — ${detail}`}`);
  if (!ok) {
    failures += 1;
  }
}

async function getJson(path) {
  const response = await fetch(`${baseUrl}${path}`, {
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  let body = null;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  return { status: response.status, body };
}

// Liveness must never depend on the database.
async function checkHealth() {
  const { status, body } = await getJson('/health');
  report(
    'GET /health returns 200',
    status === 200 && body?.status === 'ok',
    `status=${status}`,
  );
}

// Readiness proves the deployed revision actually reached MongoDB Atlas.
async function checkReady() {
  const { status, body } = await getJson('/ready');
  report(
    'GET /ready reports the database connected',
    status === 200 && body?.database === 'connected',
    `status=${status}`,
  );
}

async function checkNotFoundEnvelope() {
  const { status, body } = await getJson('/does-not-exist');
  report(
    'unknown route returns the standard 404 envelope',
    status === 404 && body?.error?.code === 'NOT_FOUND',
    `status=${status}`,
  );
}

function connectSocket(auth) {
  return new Promise((resolve) => {
    const socket = io(baseUrl, {
      transports: ['websocket'],
      auth,
      forceNew: true,
      timeout: TIMEOUT_MS,
      reconnection: false,
    });

    const finish = (outcome) => {
      socket.close();
      resolve(outcome);
    };

    socket.on('connect', () => {
      finish({ connected: true });
    });
    socket.on('connect_error', (error) => {
      finish({ connected: false, message: error.message });
    });
    setTimeout(() => {
      finish({ connected: false, message: 'timed out' });
    }, TIMEOUT_MS);
  });
}

// A rejected handshake still proves the WebSocket upgrade completed: the server had to
// receive the connection to refuse it.
async function checkSocketRejectsAnonymous() {
  const outcome = await connectSocket({});
  report(
    'WebSocket upgrade works and anonymous sockets are rejected',
    !outcome.connected && outcome.message === 'MISSING_USER_ID',
    outcome.message ?? 'connected unexpectedly',
  );
}

// The full path: create an identity over REST, then hold a real authenticated socket.
// This is the end-to-end proof that WSS works through the ingress.
async function checkSocketAcceptsIdentified() {
  const response = await fetch(`${baseUrl}/users`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ displayName: `smoke-${Date.now()}` }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });

  if (response.status !== 201) {
    report('POST /users creates a smoke identity', false, `status=${response.status}`);
    return;
  }

  const user = await response.json();
  report('POST /users creates a smoke identity', true, `id=${user.id}`);

  const outcome = await connectSocket({ userId: user.id });
  report(
    'authenticated WebSocket connection established',
    outcome.connected,
    outcome.message ?? 'connected',
  );
}

async function main() {
  console.log(`\nSmoke test against ${baseUrl}\n`);

  await checkHealth();
  await checkReady();
  await checkNotFoundEnvelope();
  await checkSocketRejectsAnonymous();

  if (skipWrite) {
    console.log('  [SKIP] authenticated WebSocket check (SMOKE_SKIP_WRITE=1)');
  } else {
    await checkSocketAcceptsIdentified();
  }

  console.log(
    failures === 0 ? '\nSmoke test passed.\n' : `\nSmoke test FAILED (${failures} check(s)).\n`,
  );
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error('\nSmoke test crashed:', error instanceof Error ? error.message : error);
  process.exit(1);
});
