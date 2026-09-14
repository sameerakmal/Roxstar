import type { Request, Response } from 'express';

import { isDatabaseReady } from '../config/database.js';
import { AppError } from '../errors/AppError.js';

// Liveness: is this process running? Deliberately does not touch MongoDB, so a
// database outage never causes an orchestrator to kill a healthy process.
export function getHealth(_req: Request, res: Response): void {
  res.status(200).json({
    status: 'ok',
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
  });
}

// Readiness: can this process serve traffic? Requires a live database.
export function getReady(_req: Request, res: Response): void {
  if (!isDatabaseReady()) {
    throw AppError.serviceUnavailable('Database is not connected');
  }

  res.status(200).json({
    status: 'ready',
    database: 'connected',
    timestamp: new Date().toISOString(),
  });
}
