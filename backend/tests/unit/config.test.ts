import { describe, expect, it } from 'vitest';

import { loadConfig } from '../../src/config/index.js';

const validEnv = {
  NODE_ENV: 'production',
  PORT: '8080',
  MONGODB_URI: 'mongodb://localhost:27017/roxstar',
  LOG_LEVEL: 'debug',
} satisfies NodeJS.ProcessEnv;

describe('loadConfig', () => {
  it('parses and coerces a valid environment', () => {
    const config = loadConfig(validEnv);

    expect(config).toEqual({
      nodeEnv: 'production',
      port: 8080,
      mongodbUri: 'mongodb://localhost:27017/roxstar',
      logLevel: 'debug',
      isProduction: true,
    });
  });

  it('applies defaults for the optional variables', () => {
    const config = loadConfig({ MONGODB_URI: validEnv.MONGODB_URI });

    expect(config.nodeEnv).toBe('development');
    expect(config.port).toBe(3000);
    expect(config.logLevel).toBe('info');
    expect(config.isProduction).toBe(false);
  });

  it('throws when the required MONGODB_URI is missing', () => {
    expect(() => loadConfig({})).toThrow(/MONGODB_URI/);
  });

  it('reports every invalid variable in one message', () => {
    const attempt = (): unknown =>
      loadConfig({ ...validEnv, PORT: 'not-a-number', LOG_LEVEL: 'loud' });

    expect(attempt).toThrow(/PORT/);
    expect(attempt).toThrow(/LOG_LEVEL/);
  });
});
