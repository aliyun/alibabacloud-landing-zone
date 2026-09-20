import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { watchWorkitem, unwatchWorkitem, getWatchers } from './api';

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    post: vi.fn(),
    delete: vi.fn(),
    get: vi.fn(),
  },
}));

describe('workitem watch api', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('posts to the watch endpoint and unwraps the WatchState payload', async () => {
    const state = { workitemId: 42, watched: true, watcherCount: 3 };
    vi.mocked(apiClient.post).mockResolvedValue({ data: state } as never);

    await expect(watchWorkitem(42)).resolves.toEqual(state);
    expect(apiClient.post).toHaveBeenCalledWith('/api/workitems/42/watch');
  });

  it('deletes the watch endpoint to unwatch and unwraps the WatchState payload', async () => {
    const state = { workitemId: 42, watched: false, watcherCount: 2 };
    vi.mocked(apiClient.delete).mockResolvedValue({ data: state } as never);

    await expect(unwatchWorkitem(42)).resolves.toEqual(state);
    expect(apiClient.delete).toHaveBeenCalledWith('/api/workitems/42/watch');
  });

  it('gets the watcher list endpoint and unwraps the participants', async () => {
    const watchers = [{ userId: 100, name: '蔡何', roleName: '关注人' }];
    vi.mocked(apiClient.get).mockResolvedValue({ data: watchers } as never);

    await expect(getWatchers('42')).resolves.toEqual(watchers);
    expect(apiClient.get).toHaveBeenCalledWith('/api/workitems/42/watchers');
  });
});
