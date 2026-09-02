import { expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { reconcileScheduledRunEvent, resetRunSequence } from './realtime';

it('invalidates the REST aggregate when a realtime sequence has a gap', () => {
  const client = new QueryClient(); const invalidate = vi.spyOn(client, 'invalidateQueries');
  resetRunSequence(7);
  reconcileScheduledRunEvent(client, 7, { channel: 'scheduled-run:7', type: 'status', payload: { sequence: 1 }, timestamp: 1 });
  reconcileScheduledRunEvent(client, 7, { channel: 'scheduled-run:7', type: 'status', payload: { sequence: 3 }, timestamp: 2 });
  expect(invalidate).toHaveBeenCalled();
});
