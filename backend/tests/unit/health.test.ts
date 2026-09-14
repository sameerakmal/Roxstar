import request from 'supertest';
import { describe, expect, it } from 'vitest';

import { createApp } from '../../src/app.js';

describe('GET /health', () => {
  // No database is connected anywhere in this suite. Passing proves liveness
  // never queries MongoDB.
  it('returns 200 with process liveness information', async () => {
    const response = await request(createApp()).get('/health');
    const body = response.body as { status: string; uptime: number; timestamp: string };

    expect(response.status).toBe(200);
    expect(body.status).toBe('ok');
    expect(typeof body.uptime).toBe('number');
    expect(typeof body.timestamp).toBe('string');
  });
});
