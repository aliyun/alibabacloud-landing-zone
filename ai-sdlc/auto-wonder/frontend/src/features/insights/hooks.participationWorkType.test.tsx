import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useHumanAgentParticipation, useHumanAgentSlowTail } from './hooks';
import { getHumanAgentParticipation, getHumanAgentSlowTail } from './api';
import type { HumanAgentParticipation, HumanAgentSlowTail } from './types';

vi.mock('./api', () => ({
  getHumanAgentParticipation: vi.fn(),
  getHumanAgentSlowTail: vi.fn(),
}));

function wrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

beforeEach(() => {
  vi.mocked(getHumanAgentParticipation).mockReset();
  vi.mocked(getHumanAgentSlowTail).mockReset();
});

it('queries participation per work type and keeps each selection in its own cache entry', async () => {
  const payload = { available: true, sampleSize: 0, trend: [] } as unknown as HumanAgentParticipation;
  vi.mocked(getHumanAgentParticipation).mockResolvedValue(payload);
  const client = newClient();

  const { result } = renderHook(
    () => useHumanAgentParticipation('2026-09-01', '2026-09-25', 'BUG'),
    { wrapper: wrapper(client) },
  );

  await waitFor(() => expect(result.current.isSuccess).toBe(true));
  expect(getHumanAgentParticipation).toHaveBeenCalledWith({
    startDate: '2026-09-01', endDate: '2026-09-25', workType: 'BUG',
  });
  expect(client.getQueryData(['human-agent-participation', '2026-09-01', '2026-09-25', 'BUG'])).toBe(payload);
});

it('defaults the slow tail query to every work type', async () => {
  const payload = { items: [] } as unknown as HumanAgentSlowTail;
  vi.mocked(getHumanAgentSlowTail).mockResolvedValue(payload);
  const client = newClient();

  const { result } = renderHook(
    () => useHumanAgentSlowTail('2026-09-01', '2026-09-25'),
    { wrapper: wrapper(client) },
  );

  await waitFor(() => expect(result.current.isSuccess).toBe(true));
  expect(getHumanAgentSlowTail).toHaveBeenCalledWith({
    startDate: '2026-09-01', endDate: '2026-09-25', workType: 'ALL',
  });
  expect(client.getQueryData(['human-agent-slow-tail', '2026-09-01', '2026-09-25', 'ALL'])).toBe(payload);
});
