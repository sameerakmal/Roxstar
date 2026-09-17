import type { NextFunction, Request, Response } from 'express';
import { ZodError } from 'zod';

import { AppError, type ErrorDetail } from '../errors/AppError.js';
import { DomainError } from '../errors/DomainError.js';
import { logger } from '../utils/logger.js';

type ErrorResponse = {
  error: {
    code: string;
    message: string;
    details: ErrorDetail[];
  };
};

// The single place where a business outcome becomes an HTTP status. Services throw
// domain errors and stay transport-free; this table is what the API contract commits to.
const DOMAIN_ERROR_STATUS: Record<string, number> = {
  UNKNOWN_USER: 401,
  ROOM_NOT_FOUND: 404,
  DRAFT_NOT_FOUND: 404,
  NOT_A_MEMBER: 403,
  DRAFT_NOT_OWNED: 403,
  ROOM_CLOSED: 409,
  NOT_ROOM_OWNER: 403,
  SPIN_NOT_FOUND: 404,
  ACTIVE_SPIN_EXISTS: 409,
  INSUFFICIENT_PLAYERS: 409,
  TOO_MANY_PLAYERS: 409,
};

function toErrorResponse(error: unknown): { statusCode: number; body: ErrorResponse } {
  if (error instanceof DomainError) {
    return {
      statusCode: DOMAIN_ERROR_STATUS[error.code] ?? 400,
      body: { error: { code: error.code, message: error.message, details: [] } },
    };
  }

  if (error instanceof AppError) {
    return {
      statusCode: error.statusCode,
      body: { error: { code: error.code, message: error.message, details: error.details } },
    };
  }

  if (error instanceof ZodError) {
    return {
      statusCode: 400,
      body: {
        error: {
          code: 'VALIDATION_ERROR',
          message: 'Request validation failed',
          details: error.issues.map((issue) => ({
            field: issue.path.join('.'),
            message: issue.message,
          })),
        },
      },
    };
  }

  // Unexpected errors never leak internals to the client; the stack goes to the log only.
  return {
    statusCode: 500,
    body: {
      error: {
        code: 'INTERNAL_ERROR',
        message: 'An unexpected error occurred',
        details: [],
      },
    },
  };
}

export function errorHandler(
  error: unknown,
  req: Request,
  res: Response,
  next: NextFunction,
): void {
  if (res.headersSent) {
    next(error);
    return;
  }

  const { statusCode, body } = toErrorResponse(error);
  const requestId = req.id as string | undefined;

  if (statusCode >= 500) {
    logger.error({ err: error, requestId }, 'Unhandled request error');
  } else {
    logger.warn({ code: body.error.code, statusCode, requestId }, body.error.message);
  }

  res.status(statusCode).json(body);
}
