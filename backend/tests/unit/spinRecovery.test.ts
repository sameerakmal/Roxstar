import { describe, expect, it } from 'vitest';

import { pendingEliminations } from '../../src/services/spinRecoveryService.js';

const INTERVAL = 5000;
const startedAt = new Date('2026-01-01T00:00:00.000Z');
const at = (seconds: number): number => startedAt.getTime() + seconds * 1000;

describe('pendingEliminations', () => {
  it('owes nothing before the first deadline', () => {
    expect(
      pendingEliminations({
        startedAt,
        now: at(4),
        intervalMs: INTERVAL,
        timerEliminatedCount: 0,
        activeCount: 5,
      }),
    ).toBe(0);
  });

  it('owes one elimination per elapsed interval', () => {
    const owed = (seconds: number): number =>
      pendingEliminations({
        startedAt,
        now: at(seconds),
        intervalMs: INTERVAL,
        timerEliminatedCount: 0,
        activeCount: 10,
      });

    expect(owed(5)).toBe(1);
    expect(owed(12)).toBe(2);
    expect(owed(30)).toBe(6);
  });

  it('subtracts eliminations that already happened', () => {
    expect(
      pendingEliminations({
        startedAt,
        now: at(20),
        intervalMs: INTERVAL,
        timerEliminatedCount: 3,
        activeCount: 5,
      }),
    ).toBe(1);
  });

  // The reason eliminationReason exists. A participant removed because they LEFT did
  // not consume a scheduled tick, so it must not be subtracted here — otherwise
  // recovery would skip a scheduled elimination and shorten the spin.
  it('ignores LEFT eliminations, which do not consume a scheduled tick', () => {
    // 20s elapsed => 4 ticks due. One TIMER elimination has happened, plus a LEFT
    // elimination that the caller correctly excludes from the count.
    expect(
      pendingEliminations({
        startedAt,
        now: at(20),
        intervalMs: INTERVAL,
        timerEliminatedCount: 1,
        activeCount: 6,
      }),
    ).toBe(3);

    // Had the LEFT elimination been counted too (2), recovery would owe only 2 and
    // silently drop a scheduled elimination.
    expect(
      pendingEliminations({
        startedAt,
        now: at(20),
        intervalMs: INTERVAL,
        timerEliminatedCount: 2,
        activeCount: 6,
      }),
    ).toBe(2);
  });

  it('never owes more than is needed to reach one winner', () => {
    // A very long outage must not eliminate everybody.
    expect(
      pendingEliminations({
        startedAt,
        now: at(60 * 60),
        intervalMs: INTERVAL,
        timerEliminatedCount: 0,
        activeCount: 4,
      }),
    ).toBe(3);
  });

  it('never returns a negative count', () => {
    expect(
      pendingEliminations({
        startedAt,
        now: at(5),
        intervalMs: INTERVAL,
        timerEliminatedCount: 9,
        activeCount: 5,
      }),
    ).toBe(0);
  });

  it('owes nothing once a single participant remains', () => {
    expect(
      pendingEliminations({
        startedAt,
        now: at(600),
        intervalMs: INTERVAL,
        timerEliminatedCount: 0,
        activeCount: 1,
      }),
    ).toBe(0);
  });

  it('is idempotent — a second run over recovered state owes nothing', () => {
    // After catching up 3 eliminations, re-running with the new counts owes 0.
    expect(
      pendingEliminations({
        startedAt,
        now: at(16),
        intervalMs: INTERVAL,
        timerEliminatedCount: 3,
        activeCount: 2,
      }),
    ).toBe(0);
  });

  it('honours a shorter injected interval', () => {
    expect(
      pendingEliminations({
        startedAt,
        now: at(1),
        intervalMs: 50,
        timerEliminatedCount: 0,
        activeCount: 30,
      }),
    ).toBe(20);
  });
});
