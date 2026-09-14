import type { Express } from 'express';
import request from 'supertest';

import { createApp } from '../../src/app.js';
import { USER_ID_HEADER } from '../../src/middleware/currentUser.js';

export const app: Express = createApp();

export async function createUser(displayName: string): Promise<string> {
  const response = await request(app).post('/users').send({ displayName });
  if (response.status !== 201) {
    throw new Error(`Failed to create user: ${String(response.status)}`);
  }
  return (response.body as { id: string }).id;
}

export async function createRoom(userId: string): Promise<string> {
  const response = await request(app).post('/rooms').set(USER_ID_HEADER, userId);
  if (response.status !== 201) {
    throw new Error(`Failed to create room: ${String(response.status)}`);
  }
  return (response.body as { room: { id: string } }).room.id;
}

export async function createDraft(userId: string, name = 'Take 1'): Promise<string> {
  const response = await request(app)
    .post('/drafts')
    .set(USER_ID_HEADER, userId)
    .send({ name, durationMs: 3000, fileLocation: `/drafts/${name}.wav`, effect: 'ECHO' });
  if (response.status !== 201) {
    throw new Error(`Failed to create draft: ${String(response.status)}`);
  }
  return (response.body as { id: string }).id;
}

export const asUser = (userId: string): [string, string] => [USER_ID_HEADER, userId];
