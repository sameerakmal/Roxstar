import { afterEach, describe, expect, it, vi } from 'vitest';

import * as scheduler from '../../src/services/spinScheduler.js';
import { addSocket, clearPresence, removeSocket } from '../../src/services/presenceRegistry.js';
import { withRoomLock } from '../../src/utils/roomMutex.js';

afterEach(() => {
  scheduler.cancelAll();
  scheduler.setEliminationIntervalMs(5000);
  clearPresence();
});

describe('spinScheduler deadlines', () => {
  const startedAt = new Date('2026-01-01T00:00:00.000Z');

  it('spaces deadlines by the configured interval', () => {
    scheduler.setEliminationIntervalMs(5000);

    expect(scheduler.deadlineFor(startedAt, 1)).toBe(startedAt.getTime() + 5000);
    expect(scheduler.deadlineFor(startedAt, 4)).toBe(startedAt.getTime() + 20000);
  });

  // Deadlines are absolute, so a tick that fires late does not push the rest of the
  // schedule back — the next delay simply shrinks.
  it('does not accumulate drift when a tick is late', () => {
    scheduler.setEliminationIntervalMs(5000);
    const third = scheduler.deadlineFor(startedAt, 3);

    // Tick 2 ran 4 seconds late; tick 3 is still due at its original absolute time.
    const lateNow = startedAt.getTime() + 14000;
    expect(scheduler.delayUntil(third, lateNow)).toBe(1000);
  });

  it('returns zero delay for a deadline already passed', () => {
    const past = scheduler.deadlineFor(startedAt, 1);
    expect(scheduler.delayUntil(past, startedAt.getTime() + 60000)).toBe(0);
  });

  it('supports an injected short interval for tests', () => {
    scheduler.setEliminationIntervalMs(40);
    expect(scheduler.deadlineFor(startedAt, 2)).toBe(startedAt.getTime() + 80);
  });
});

describe('spinScheduler timer ownership', () => {
  it('keeps at most one timer per spin', () => {
    scheduler.schedule('spin-1', 10_000, () => Promise.resolve());
    scheduler.schedule('spin-1', 10_000, () => Promise.resolve());
    scheduler.schedule('spin-2', 10_000, () => Promise.resolve());

    expect(scheduler.scheduledCount()).toBe(2);
    expect(scheduler.hasTimer('spin-1')).toBe(true);
  });

  it('replaces rather than duplicates when rescheduled', async () => {
    const first = vi.fn(() => Promise.resolve());
    const second = vi.fn(() => Promise.resolve());

    scheduler.schedule('spin-1', 5, first);
    scheduler.schedule('spin-1', 5, second);

    await new Promise((resolve) => setTimeout(resolve, 40));

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('cancels a timer so it never fires', async () => {
    const task = vi.fn(() => Promise.resolve());
    scheduler.schedule('spin-1', 5, task);
    scheduler.cancel('spin-1');

    await new Promise((resolve) => setTimeout(resolve, 40));

    expect(task).not.toHaveBeenCalled();
    expect(scheduler.hasTimer('spin-1')).toBe(false);
  });

  it('swallows a failing tick without crashing the process', async () => {
    scheduler.schedule('spin-1', 5, () => Promise.reject(new Error('boom')));

    await new Promise((resolve) => setTimeout(resolve, 40));

    expect(scheduler.hasTimer('spin-1')).toBe(false);
  });
});

describe('presenceRegistry', () => {
  const room = 'room-1';
  const user = 'user-1';

  it('reports the first socket distinctly from later ones', () => {
    expect(addSocket(room, user, 'socket-a')).toBe('FIRST_CONNECTION');
    expect(addSocket(room, user, 'socket-b')).toBe('ADDITIONAL_CONNECTION');
    expect(scheduler.scheduledCount()).toBe(0);
  });

  // Multiple connections for one user: closing one tab must not look like leaving.
  it('only reports a last disconnection when every socket is gone', () => {
    addSocket(room, user, 'socket-a');
    addSocket(room, user, 'socket-b');

    expect(removeSocket(room, user, 'socket-a')).toBe('REMAINING_CONNECTIONS');
    expect(removeSocket(room, user, 'socket-b')).toBe('LAST_DISCONNECTION');
  });

  it('ignores an unknown socket', () => {
    expect(removeSocket(room, user, 'never-added')).toBe('NOT_PRESENT');
  });

  it('keeps users and rooms independent', () => {
    addSocket(room, user, 'socket-a');
    addSocket(room, 'user-2', 'socket-b');
    addSocket('room-2', user, 'socket-c');

    expect(removeSocket(room, user, 'socket-a')).toBe('LAST_DISCONNECTION');
    expect(removeSocket(room, 'user-2', 'socket-b')).toBe('LAST_DISCONNECTION');
    expect(removeSocket('room-2', user, 'socket-c')).toBe('LAST_DISCONNECTION');
  });
});

describe('roomMutex', () => {
  it('runs tasks for one room strictly in order', async () => {
    const order: number[] = [];

    await Promise.all([
      withRoomLock('room-1', async () => {
        await new Promise((resolve) => setTimeout(resolve, 20));
        order.push(1);
      }),
      withRoomLock('room-1', () => {
        order.push(2);
        return Promise.resolve();
      }),
      withRoomLock('room-1', () => {
        order.push(3);
        return Promise.resolve();
      }),
    ]);

    expect(order).toEqual([1, 2, 3]);
  });

  it('does not let one room block another', async () => {
    const order: string[] = [];

    await Promise.all([
      withRoomLock('slow', async () => {
        await new Promise((resolve) => setTimeout(resolve, 30));
        order.push('slow');
      }),
      withRoomLock('fast', () => {
        order.push('fast');
        return Promise.resolve();
      }),
    ]);

    expect(order).toEqual(['fast', 'slow']);
  });

  it('keeps serving a room after a task throws', async () => {
    await expect(
      withRoomLock('room-1', () => Promise.reject(new Error('boom'))),
    ).rejects.toThrow('boom');

    await expect(withRoomLock('room-1', () => Promise.resolve('recovered'))).resolves.toBe(
      'recovered',
    );
  });
});
