import { pinoHttp } from 'pino-http';

import { logger } from '../utils/logger.js';

export const requestLogger = pinoHttp({
  logger,
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
