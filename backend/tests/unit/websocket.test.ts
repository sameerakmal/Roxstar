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

describe('Socket.IO server', () => {
  it('initializes and accepts a client connection', async () => {
    httpServer = createServer();
    ioServer = initializeSocketServer(httpServer);

    await new Promise<void>((resolve) => {
      httpServer.listen(0, resolve);
    });

    const { port } = httpServer.address() as AddressInfo;
    client = createClient(`http://localhost:${String(port)}`);

    await new Promise<void>((resolve, reject) => {
      client.on('connect', resolve);
      client.on('connect_error', reject);
    });

    expect(client.connected).toBe(true);
  });
});
