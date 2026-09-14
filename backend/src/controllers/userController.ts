import type { Request, Response } from 'express';
import { z } from 'zod';

import { validatedBody } from '../middleware/validateRequest.js';
import { createUser } from '../services/userService.js';

export const createUserSchema = z.object({
  displayName: z.string().trim().min(1).max(50),
});

type CreateUserBody = z.infer<typeof createUserSchema>;

export async function postUser(_req: Request, res: Response): Promise<void> {
  const { displayName } = validatedBody<CreateUserBody>(res);
  const user = await createUser(displayName);
  res.status(201).json(user);
}
