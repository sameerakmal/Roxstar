import type { NextFunction, Request, Response } from 'express';
import { Types } from 'mongoose';
import { z, type ZodType } from 'zod';

// A path parameter that must be a MongoDB ObjectId. Rejecting malformed ids here
// keeps "not found" meaning "does not exist" rather than "was unparseable".
export const objectIdSchema = z
  .string()
  .refine((value) => Types.ObjectId.isValid(value), { message: 'must be a valid id' })
  .transform((value) => new Types.ObjectId(value));

// Validated values are attached to res.locals rather than reassigned onto req, since
// req.params is a getter in Express 5.
export function validateBody<T>(schema: ZodType<T>) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      next(parsed.error);
      return;
    }
    res.locals['body'] = parsed.data;
    next();
  };
}

export function validateParams<T>(schema: ZodType<T>) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const parsed = schema.safeParse(req.params);
    if (!parsed.success) {
      next(parsed.error);
      return;
    }
    res.locals['params'] = parsed.data;
    next();
  };
}

export function validatedBody<T>(res: Response): T {
  return res.locals['body'] as T;
}

export function validatedParams<T>(res: Response): T {
  return res.locals['params'] as T;
}
