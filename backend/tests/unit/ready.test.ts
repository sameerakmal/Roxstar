import request from 'supertest';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createApp } from '../../src/app.js';
import { isDatabaseReady } from '../../src/config/database.js';

vi.mock('../../src/config/database.js', () => ({
  isDatabaseReady: vi.fn(),
  connectDatabase: vi.fn(),
  disconnectDatabase: vi.fn(),
}));

const mockIsDatabaseReady = vi.mocked(isDatabaseReady);

describe('GET /ready', () => {
  beforeEach(() => {
    mockIsDatabaseReady.mockReset();
  });

  it('returns 200 when the database is connected', async () => {
    mockIsDatabaseReady.mockReturnValue(true);

    const response = await request(createApp()).get('/ready');

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ status: 'ready', database: 'connected' });
  });

  it('returns 503 in the standard error envelope when the database is unavailable', async () => {
    mockIsDatabaseReady.mockReturnValue(false);

    const response = await request(createApp()).get('/ready');

    expect(response.status).toBe(503);
    expect(response.body).toEqual({
      error: {
        code: 'SERVICE_UNAVAILABLE',
        message: 'Database is not connected',
        details: [],
      },
    });
  });
});
