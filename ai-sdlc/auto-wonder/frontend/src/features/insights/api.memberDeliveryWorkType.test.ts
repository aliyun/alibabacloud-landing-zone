import { beforeEach, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { getMemberDelivery } from './api';

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
  await getMemberDelivery({ startDate: '2026-09-01', endDate: '2026-09-25', workType: 'BUG' });
  expect(apiClient.get).toHaveBeenLastCalledWith('/api/insights/member-delivery', {
    params: { start_date: '2026-09-01', end_date: '2026-09-25', work_type: 'BUG' },
  });

  await getMemberDelivery({ startDate: '2026-09-01', endDate: '2026-09-25', workType: 'ALL' });
  expect(lastRequestParams()).toEqual({ start_date: '2026-09-01', end_date: '2026-09-25' });
  expect(Object.keys(lastRequestParams())).not.toContain('work_type');

  await getMemberDelivery({ startDate: '2026-09-01', endDate: '2026-09-25' });
  expect(lastRequestParams()).toEqual({ start_date: '2026-09-01', end_date: '2026-09-25' });
});
