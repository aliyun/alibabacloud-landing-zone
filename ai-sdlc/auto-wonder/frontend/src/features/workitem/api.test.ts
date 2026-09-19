import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { getArtifactDownloadUrl, getRuntimeActivities } from './api';

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
  },
}));

describe('workitem API', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads the complete runtime activity snapshot without an afterSeq parameter', async () => {
    const timeline = {
      dispatchId: 301,
      activities: [{
        eventId: '301:1',
        seq: 1,
        eventTime: '2026-09-02T10:00:00Z',
        eventType: 'agent.message',
        level: 'INFO' as const,
        content: '正在分析工单评论。',
      }],
    };
    vi.mocked(apiClient.get).mockResolvedValue({ data: timeline } as never);

    await expect(getRuntimeActivities(301)).resolves.toEqual(timeline);

    expect(apiClient.get).toHaveBeenCalledWith('/api/dispatches/301/runtime-trace/activities', undefined);
  });

  it('forwards the caller AbortSignal to the runtime activity request', async () => {
    const controller = new AbortController();
    const timeline = { dispatchId: 302, activities: [] };
    vi.mocked(apiClient.get).mockResolvedValue({ data: timeline } as never);

    await expect(getRuntimeActivities(302, controller.signal)).resolves.toEqual(timeline);

    expect(apiClient.get).toHaveBeenCalledWith(
      '/api/dispatches/302/runtime-trace/activities',
      { signal: controller.signal },
    );
  });

  it('returns an http presigned download URL unchanged', async () => {
    const url = 'http://172.19.133.124:9000/b/k?X-Amz-Expires=600&X-Amz-Signature=s';
    vi.mocked(apiClient.get).mockResolvedValue({ data: url } as never);

    await expect(getArtifactDownloadUrl(7)).resolves.toBe(url);

    expect(apiClient.get).toHaveBeenCalledWith('/api/artifacts/7/download');
  });

  it('returns an https presigned download URL unchanged', async () => {
    const url = 'https://172.19.133.124:9000/b/k?X-Amz-Expires=600&X-Amz-Signature=s';
    vi.mocked(apiClient.get).mockResolvedValue({ data: url } as never);

    await expect(getArtifactDownloadUrl(7)).resolves.toBe(url);

    expect(apiClient.get).toHaveBeenCalledWith('/api/artifacts/7/download');
  });
});
