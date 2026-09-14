import { describe, expect, it } from 'vitest';

import {
  ActiveSpinExistsError,
  DuplicateKeyError,
  duplicateKeyIndexName,
  isDuplicateKeyError,
} from '../../src/errors/RepositoryError.js';

describe('isDuplicateKeyError', () => {
  it('recognises a MongoDB duplicate-key error', () => {
    const error = Object.assign(new Error('E11000 duplicate key'), { code: 11000 });

    expect(isDuplicateKeyError(error)).toBe(true);
  });

  it('ignores other errors', () => {
    expect(isDuplicateKeyError(new Error('boom'))).toBe(false);
    expect(isDuplicateKeyError(Object.assign(new Error('x'), { code: 121 }))).toBe(false);
    expect(isDuplicateKeyError(null)).toBe(false);
  });
});

describe('duplicateKeyIndexName', () => {
  it('extracts the violated index name from the driver message', () => {
    const error = Object.assign(
      new Error(
        'E11000 duplicate key error collection: roxstar.spins index: uniq_active_spin_per_room dup key: { roomId: 1 }',
      ),
      { code: 11000 },
    );

    expect(duplicateKeyIndexName(error)).toBe('uniq_active_spin_per_room');
  });

  it('falls back when the message has no index name', () => {
    const error = Object.assign(new Error('E11000 duplicate key'), { code: 11000 });

    expect(duplicateKeyIndexName(error)).toBe('unknown');
  });
});

describe('domain errors', () => {
  it('ActiveSpinExistsError carries the room id', () => {
    const error = new ActiveSpinExistsError('room-1');

    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('ActiveSpinExistsError');
    expect(error.roomId).toBe('room-1');
  });

  it('DuplicateKeyError carries the index name', () => {
    const error = new DuplicateKeyError('uniq_active_membership', 'already a member');

    expect(error.indexName).toBe('uniq_active_membership');
  });
});
