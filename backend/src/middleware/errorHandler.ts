import type { NextFunction, Request, Response } from 'express';
import { ZodError } from 'zod';

import { AppError, type ErrorDetail } from '../errors/AppError.js';
import { logger } from '../utils/logger.js';

type ErrorResponse = {
  error: {
    code: string;
    message: string;
    details: ErrorDetail[];
  };
};

function toErrorResponse(error: unknown): { statusCode: number; body: ErrorResponse } {
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
  _req: Request,
  res: Response,
  next: NextFunction,
): void {
  if (res.headersSent) {
    next(error);
    return;
  }

  const { statusCode, body } = toErrorResponse(error);

  if (statusCode >= 500) {
    logger.error({ err: error }, 'Unhandled request error');
  } else {
    logger.warn({ code: body.error.code, statusCode }, body.error.message);
  }

  res.status(statusCode).json(body);
}
