import crypto from 'node:crypto';
import { pinoHttp } from 'pino-http';

import { logger } from '../utils/logger.js';

export const requestLogger = pinoHttp({
  logger,
  genReqId: (req, res) => {
    const existing = req.headers['x-request-id'];
    const id =
      typeof existing === 'string' && existing.trim().length > 0
        ? existing.trim()
        : crypto.randomUUID();
    res.setHeader('x-request-id', id);
    return id;
  },
  customProps: (req) => {
    const userId = req.headers['x-user-id'];
    return {
      requestId: req.id,
      ...(typeof userId === 'string' && userId.length > 0 ? { userId } : {}),
    };
  },
  customLogLevel: (_req, res, error) => {
    // 503 is the readiness endpoint reporting a known dependency outage: expected,
    // already handled, and not something that should page anyone. Real faults (500)
    // stay at error level.
    if (res.statusCode === 503) {
      return 'warn';
    }
    if (error !== undefined || res.statusCode >= 500) {
      return 'error';
    }
    if (res.statusCode >= 400) {
      return 'warn';
    }
    return 'info';
  },
});

