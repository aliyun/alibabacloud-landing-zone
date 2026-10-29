import { describe, expect, it } from 'vitest';
import { executionActionLabel, resolveExecutionActions } from './executionControl';
import type { DispatchAttempt } from '@/shared/types/workitem';
import { i18n } from '@/shared/i18n';

function attempt(overrides: Partial<DispatchAttempt> = {}): DispatchAttempt {
  return {
    dispatchId: 30,
    agentName: 'RD',
    executorName: null,
    status: 'RUNNING',
    startedAt: null,
    durationMs: null,
    ...overrides,
  };
}

const allowed = { allowed: true };
const denied = { allowed: false, reasonKey: 'delivery.controlReason.notPauseable' };

describe('resolveExecutionActions', () => {
  it('uses pause as the primary action and end as the danger overflow while running', () => {
    const resolved = resolveExecutionActions(attempt({
      control: { pause: allowed, end: allowed, forceEnd: { allowed: false }, resume: { allowed: false } },
    }));
    expect(resolved.primary).toBe('pause');
    expect(resolved.overflow).toEqual(['end']);
  });

  it('offers end while a normal pause is still pending and hides force until cancel intent exists', () => {
    const resolved = resolveExecutionActions(attempt({
      status: 'PAUSING',
      control: { pause: denied, end: allowed, forceEnd: { allowed: false }, resume: { allowed: false } },
    }));
    expect(resolved.primary).toBe('end');
    expect(resolved.overflow).toEqual([]);
  });

  it('makes force end primary when the execution is already stopping', () => {
    const resolved = resolveExecutionActions(attempt({
      status: 'PAUSING',
      cancelRequested: true,
      stopPending: true,
      control: { pause: denied, end: { allowed: false }, forceEnd: allowed, resume: { allowed: false } },
    }));
    expect(resolved.primary).toBe('forceEnd');
    expect(resolved.overflow).toEqual([]);
  });

  it('uses the server resume target for retry labels', () => {
    const value = attempt({
      status: 'FAILED',
      control: {
        pause: { allowed: false },
        end: { allowed: false },
        forceEnd: { allowed: false },
        resume: { allowed: true, mode: 'RETRY', targetDispatchId: 58 },
      },
    });
    expect(resolveExecutionActions(value)).toEqual({ primary: 'resume', overflow: [], disabled: [] });
    expect(executionActionLabel(value, 'resume', (key, options) =>
      String(i18n.t(`workitem:${key}` as never, options as never)))).toContain('#58');
  });

  it('keeps relevant denied actions and their reasons instead of hiding them', () => {
    const resolved = resolveExecutionActions(attempt({
      status: 'PAUSING',
      control: {
        pause: { allowed: false, reasonKey: 'delivery.controlReason.pausing' },
        end: { allowed: true },
        forceEnd: { allowed: false },
        resume: { allowed: false },
      },
    }));
    expect(resolved.primary).toBe('end');
    expect(resolved.disabled).toEqual([
      { action: 'pause', reasonKey: 'delivery.controlReason.pausing', reasonArgs: [] },
    ]);
  });

  it('keeps the server reason for a blocked formal retry', () => {
    const resolved = resolveExecutionActions(attempt({
      status: 'FAILED',
      control: {
        pause: { allowed: false },
        end: { allowed: false },
        forceEnd: { allowed: false },
        resume: { allowed: false, reasonKey: 'delivery.controlReason.newer', reasonArgs: ['58', '301'] },
      },
    }));
    expect(resolved.disabled).toEqual([
      { action: 'resume', reasonKey: 'delivery.controlReason.newer', reasonArgs: ['58', '301'] },
    ]);
  });
});
