import express from 'express';
import request from 'supertest';
import { describe, expect, it } from 'vitest';

import { createApp } from '../src/app.js';
import { errorHandler } from '../src/middleware/errorHandler.js';

describe('404 handling', () => {
  it('returns the standard error envelope for an unknown route', async () => {
    const response = await request(createApp()).get('/does-not-exist');
    const body = response.body as { error: { code: string; details: unknown[] } };

    expect(response.status).toBe(404);
    expect(body.error.code).toBe('NOT_FOUND');
    expect(body.error.details).toEqual([]);
  });
});

describe('error handler', () => {
  it('converts an unexpected error into a 500 without leaking internals', async () => {
    const app = express();
    app.get('/boom', () => {
      throw new Error('database password is hunter2');
    });
    app.use(errorHandler);

    const response = await request(app).get('/boom');

    expect(response.status).toBe(500);
    expect(response.body).toEqual({
      error: {
        code: 'INTERNAL_ERROR',
        message: 'An unexpected error occurred',
        details: [],
      },
    });
    expect(JSON.stringify(response.body)).not.toContain('hunter2');
  });
});
