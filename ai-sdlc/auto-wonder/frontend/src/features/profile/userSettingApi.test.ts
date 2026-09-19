import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import {
  USER_SETTINGS_QUERY_KEY,
  deleteMySetting,
  getMySetting,
  listMySettings,
  putMySetting,
  userSettingQueryKey,
} from './userSettingApi';

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe('userSettingApi', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('reads a single preference from the logged-in user scope rather than a query param', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(
      { data: { key: 'clarification_send_mode', valueJson: '"enter"' } } as never,
    );

    await expect(getMySetting('clarification_send_mode')).resolves.toEqual({
      key: 'clarification_send_mode',
      valueJson: '"enter"',
    });
    expect(apiClient.get).toHaveBeenCalledWith('/api/users/me/settings/clarification_send_mode');
  });

  it('escapes the key so a key containing a slash cannot address another endpoint', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: null } as never);

    await getMySetting('a/b');

    expect(apiClient.get).toHaveBeenCalledWith('/api/users/me/settings/a%2Fb');
  });

  it('writes the value under valueJson so any JSON shape can be stored', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: null } as never);

    await putMySetting('clarification_send_mode', '"shift-enter"');

    expect(apiClient.put).toHaveBeenCalledWith(
      '/api/users/me/settings/clarification_send_mode',
      { valueJson: '"shift-enter"' },
    );
  });

  it('sends an explicit null value when a preference is being cleared', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: null } as never);

    await putMySetting('clarification_send_mode', null);

    expect(apiClient.put).toHaveBeenCalledWith(
      '/api/users/me/settings/clarification_send_mode',
      { valueJson: null },
    );
  });

  it('deletes only the caller-scoped key', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue({ data: null } as never);

    await deleteMySetting('clarification_send_mode');

    expect(apiClient.delete).toHaveBeenCalledWith('/api/users/me/settings/clarification_send_mode');
  });

  it('lists every preference of the logged-in user in one call', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [{ key: 'a', valueJson: '1' }] } as never);

    await expect(listMySettings()).resolves.toEqual([{ key: 'a', valueJson: '1' }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/users/me/settings');
  });

  it('names the cache entry after the key so two preferences never share one entry', () => {
    expect(userSettingQueryKey('a')).toEqual(['user-setting', 'a']);
    expect(userSettingQueryKey('a')).not.toEqual(userSettingQueryKey('b'));
    expect(USER_SETTINGS_QUERY_KEY).toEqual(['user-settings']);
  });
});
