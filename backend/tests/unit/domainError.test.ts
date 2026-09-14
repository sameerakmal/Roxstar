import express from 'express';
import request from 'supertest';
import { describe, expect, it } from 'vitest';

import {
  DraftNotFoundError,
  DraftNotOwnedError,
  NotAMemberError,
  RoomClosedError,
  RoomNotFoundError,
  UserNotFoundError,
} from '../../src/errors/DomainError.js';
import { errorHandler } from '../../src/middleware/errorHandler.js';

function appThrowing(error: Error): express.Express {
  const app = express();
  app.get('/boom', () => {
    throw error;
  });
  app.use(errorHandler);
  return app;
}

describe('domain error to HTTP mapping', () => {
  const cases: [Error, number, string][] = [
    [new UserNotFoundError('u1'), 401, 'UNKNOWN_USER'],
    [new RoomNotFoundError('r1'), 404, 'ROOM_NOT_FOUND'],
    [new DraftNotFoundError('d1'), 404, 'DRAFT_NOT_FOUND'],
    [new NotAMemberError('r1'), 403, 'NOT_A_MEMBER'],
    [new DraftNotOwnedError('d1'), 403, 'DRAFT_NOT_OWNED'],
    [new RoomClosedError('r1'), 409, 'ROOM_CLOSED'],
  ];

  it.each(cases)('maps %s to the right status and code', async (error, status, code) => {
    const response = await request(appThrowing(error)).get('/boom');

    expect(response.status).toBe(status);
    expect((response.body as { error: { code: string } }).error.code).toBe(code);
  });

  it('keeps the standard error envelope', async () => {
    const response = await request(appThrowing(new RoomNotFoundError('r1'))).get('/boom');

    expect(response.body).toEqual({
      error: {
        code: 'ROOM_NOT_FOUND',
        message: 'No room exists with id r1',
        details: [],
      },
    });
  });
});

describe('domain error identity', () => {
  it('carries a stable code and a useful name', () => {
    const error = new NotAMemberError('room-1');

    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('NotAMemberError');
    expect(error.code).toBe('NOT_A_MEMBER');
  });
});
