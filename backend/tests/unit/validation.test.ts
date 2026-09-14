import { Types } from 'mongoose';
import { describe, expect, it } from 'vitest';

import { createDraftSchema, shareDraftSchema } from '../../src/controllers/draftController.js';
import { roomParamsSchema } from '../../src/controllers/roomController.js';
import { createUserSchema } from '../../src/controllers/userController.js';
import { objectIdSchema } from '../../src/middleware/validateRequest.js';

describe('objectIdSchema', () => {
  it('accepts a valid id and converts it to an ObjectId', () => {
    const id = new Types.ObjectId().toString();
    const parsed = objectIdSchema.parse(id);

    expect(parsed).toBeInstanceOf(Types.ObjectId);
    expect(parsed.toString()).toBe(id);
  });

  it('rejects malformed ids', () => {
    expect(() => objectIdSchema.parse('nope')).toThrow();
    expect(() => objectIdSchema.parse('')).toThrow();
  });
});

describe('createUserSchema', () => {
  it('trims the display name', () => {
    expect(createUserSchema.parse({ displayName: '  Ada  ' }).displayName).toBe('Ada');
  });

  it('rejects empty and overlong names', () => {
    expect(() => createUserSchema.parse({ displayName: '   ' })).toThrow();
    expect(() => createUserSchema.parse({ displayName: 'x'.repeat(51) })).toThrow();
  });
});

describe('createDraftSchema', () => {
  const valid = { name: 'Take 1', durationMs: 1000, fileLocation: '/a.wav' };

  it('accepts a draft without an effect', () => {
    expect(createDraftSchema.parse(valid).effect).toBeUndefined();
  });

  it('accepts the allowed effects', () => {
    for (const effect of ['NONE', 'ECHO', 'REVERB', 'PITCH_SHIFT']) {
      expect(createDraftSchema.parse({ ...valid, effect }).effect).toBe(effect);
    }
  });

  it('rejects an unknown effect and a fractional or negative duration', () => {
    expect(() => createDraftSchema.parse({ ...valid, effect: 'AUTOTUNE' })).toThrow();
    expect(() => createDraftSchema.parse({ ...valid, durationMs: 1.5 })).toThrow();
    expect(() => createDraftSchema.parse({ ...valid, durationMs: -1 })).toThrow();
  });

  it('reports the offending field', () => {
    const result = createDraftSchema.safeParse({ ...valid, name: '' });

    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual(['name']);
  });
});

describe('param and share schemas', () => {
  it('parses a room id param', () => {
    const roomId = new Types.ObjectId().toString();

    expect(roomParamsSchema.parse({ roomId }).roomId.toString()).toBe(roomId);
  });

  it('requires a valid draftId when sharing', () => {
    expect(() => shareDraftSchema.parse({ draftId: 'nope' })).toThrow();
    expect(() => shareDraftSchema.parse({})).toThrow();
  });
});
