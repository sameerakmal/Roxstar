import { createServer, type Server as HttpServer } from 'node:http';
import type { AddressInfo } from 'node:net';

import { io as createClient, type Socket as ClientSocket } from 'socket.io-client';
import type { Server as SocketIOServer } from 'socket.io';
import { afterEach, describe, expect, it } from 'vitest';

import { initializeSocketServer } from '../../src/websocket/index.js';

let httpServer: HttpServer;
let ioServer: SocketIOServer;
let client: ClientSocket;

afterEach(async () => {
  client?.close();
  await ioServer?.close();
  httpServer?.close();
});

async function listen(): Promise<number> {
  httpServer = createServer();
  ioServer = initializeSocketServer(httpServer);
  await new Promise<void>((resolve) => {
    httpServer.listen(0, resolve);
  });
  return (httpServer.address() as AddressInfo).port;
}

describe('Socket.IO server', () => {
  it('initializes and listens', async () => {
    const port = await listen();

    expect(port).toBeGreaterThan(0);
    expect(ioServer).toBeDefined();
  });

  // Identity is enforced during the handshake, so an anonymous socket never reaches a
  // handler. Accepting an identified connection needs a real user and is covered by
  // the socket integration suite.
  it('rejects a connection with no identity', async () => {
    const port = await listen();
    client = createClient(`http://localhost:${String(port)}`, { transports: ['websocket'] });

    const message = await new Promise<string>((resolve, reject) => {
      client.on('connect_error', (error: Error) => {
        resolve(error.message);
      });
      client.on('connect', () => {
        reject(new Error('expected the connection to be rejected'));
      });
    });

    expect(message).toBe('MISSING_USER_ID');
  });

  it('rejects a malformed user id', async () => {
    const port = await listen();
    client = createClient(`http://localhost:${String(port)}`, {
      transports: ['websocket'],
      auth: { userId: 'not-an-id' },
    });

    const message = await new Promise<string>((resolve, reject) => {
      client.on('connect_error', (error: Error) => {
        resolve(error.message);
      });
      client.on('connect', () => {
        reject(new Error('expected the connection to be rejected'));
      });
    });

    expect(message).toBe('INVALID_USER_ID');
  });
});
