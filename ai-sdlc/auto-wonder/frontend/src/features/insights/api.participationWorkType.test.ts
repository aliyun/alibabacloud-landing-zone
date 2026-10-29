import { beforeEach, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { getHumanAgentParticipation, getHumanAgentSlowTail } from './api';

vi.mock('@/shared/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));

function lastRequestParams() {
  const call = vi.mocked(apiClient.get).mock.calls.at(-1)!;
  return (call[1] as { params: Record<string, string> }).params;
}

beforeEach(() => {
  vi.mocked(apiClient.get).mockReset();
  vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
});

it('sends the selected work type and keeps the all-workitems request byte-for-byte compatible', async () => {
  await getHumanAgentParticipation({ startDate: '2026-09-01', endDate: '2026-09-25', workType: 'REQ' });
  expect(apiClient.get).toHaveBeenLastCalledWith('/api/insights/human-agent-participation', {
    params: { start_date: '2026-09-01', end_date: '2026-09-25', work_type: 'REQ' },
  });

  await getHumanAgentParticipation({ startDate: '2026-09-01', endDate: '2026-09-25', workType: 'ALL' });
  expect(apiClient.get).toHaveBeenLastCalledWith('/api/insights/human-agent-participation', {
    params: { start_date: '2026-09-01', end_date: '2026-09-25' },
  });
  expect(Object.keys(lastRequestParams())).not.toContain('work_type');

  await getHumanAgentParticipation({ startDate: '2026-09-01', endDate: '2026-09-25' });
  expect(lastRequestParams()).toEqual({ start_date: '2026-09-01', end_date: '2026-09-25' });
});

it('forwards the work type to the slow tail query and omits it for all workitems', async () => {
  await getHumanAgentSlowTail({ startDate: '2026-09-01', endDate: '2026-09-25', workType: 'TASK' });
  expect(apiClient.get).toHaveBeenLastCalledWith('/api/insights/human-agent-participation/slowest', {
    params: { start_date: '2026-09-01', end_date: '2026-09-25', work_type: 'TASK' },
  });

  await getHumanAgentSlowTail({ startDate: '2026-09-01', endDate: '2026-09-25', workType: 'ALL' });
  expect(lastRequestParams()).toEqual({ start_date: '2026-09-01', end_date: '2026-09-25' });
  expect(Object.keys(lastRequestParams())).not.toContain('work_type');
});
