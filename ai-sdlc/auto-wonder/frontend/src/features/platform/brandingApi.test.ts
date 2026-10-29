import { describe, it, expect } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import {
  DEFAULT_BRANDING,
  IM_SELECTION_QUERY_KEY,
  PLATFORM_IM_CHANNELS_QUERY_KEY,
  getGroupChatConfig,
  getImSelection,
  groupChatQueryKey,
  selectImProvider,
  updateGroupChatConfig,
  type GroupChatConfig,
} from './brandingApi';

function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

const selection = { provider: 'FEISHU', generation: 3, activatedAt: '2026-08-06T14:43:07Z' };
const groupChat: GroupChatConfig = {
  provider: 'FEISHU',
  configured: true,
  ready: true,
  desiredEnabled: true,
  selected: true,
  appKey: 'cli_shared',
  robotCode: null,
  secretConfigured: true,
  verificationTokenConfigured: true,
  encryptKeyConfigured: false,
  version: 7,
  routeVersion: 4,
  selectionGeneration: 3,
  status: 'ACTIVE',
  drainingCount: 0,
  failedCount: 0,
  unknownCount: 0,
};

describe('DEFAULT_BRANDING', () => {
  it('pins the recommended runtime version consumers fall back to', () => {
    expect(DEFAULT_BRANDING.recommendedRuntimeVersion).toBe('0.3.30');
  });
});

describe('shared group-chat configuration API', () => {
  it('pins the selection and per-provider group-chat query keys', () => {
    expect(IM_SELECTION_QUERY_KEY).toEqual(['platform-im-selection']);
    expect(PLATFORM_IM_CHANNELS_QUERY_KEY).toEqual(['platform-im-channels']);
    expect(groupChatQueryKey('FEISHU')).toEqual(['platform-im-group-chat', 'FEISHU']);
  });

  it('reads the authoritative provider selection', async () => {
    let path = '';
    server.use(http.get('/api/platform/im-channels/selection', ({ request }) => {
      path = new URL(request.url).pathname;
      return ok(selection);
    }));

    await expect(getImSelection()).resolves.toEqual(selection);
    expect(path).toBe('/api/platform/im-channels/selection');
  });

  it('switches the provider with the expected generation', async () => {
    let method = '';
    let body: unknown = null;
    server.use(http.put('/api/platform/im-channels/selection', async ({ request }) => {
      method = request.method;
      body = await request.json();
      return ok({ ...selection, generation: 4 });
    }));

    const saved = await selectImProvider('FEISHU', 3);

    expect(method).toBe('PUT');
    expect(body).toEqual({ provider: 'FEISHU', expectedGeneration: 3 });
    expect(saved.generation).toBe(4);
  });

  it('reads and updates the group-chat configuration for one provider', async () => {
    let readPath = '';
    let writePath = '';
    let writeBody: unknown = null;
    server.use(
      http.get('/api/platform/im-channels/FEISHU/group-chat', ({ request }) => {
        readPath = new URL(request.url).pathname;
        return ok(groupChat);
      }),
      http.put('/api/platform/im-channels/FEISHU/group-chat', async ({ request }) => {
        writePath = new URL(request.url).pathname;
        writeBody = await request.json();
        return ok({ ...groupChat, version: 8, status: 'DRAINING', drainingCount: 1 });
      }),
    );

    await expect(getGroupChatConfig('FEISHU')).resolves.toMatchObject({ appKey: 'cli_shared', version: 7 });
    const saved = await updateGroupChatConfig('FEISHU', {
      enabled: true,
      version: 7,
      verificationToken: 'token',
      clearEncryptKey: true,
    });

    expect(readPath).toBe('/api/platform/im-channels/FEISHU/group-chat');
    expect(writePath).toBe('/api/platform/im-channels/FEISHU/group-chat');
    expect(writeBody).toEqual({ enabled: true, version: 7, verificationToken: 'token', clearEncryptKey: true });
    expect(saved).toMatchObject({ version: 8, status: 'DRAINING', drainingCount: 1 });
  });

  it('surfaces a stale-generation conflict from the selection endpoint', async () => {
    server.use(http.put('/api/platform/im-channels/selection', () => HttpResponse.json({
      success: false,
      code: '10500',
      message: '渠道选择已变化，请刷新后重试',
      traceId: null,
      data: null,
    })));

    await expect(selectImProvider('FEISHU', 1)).rejects.toMatchObject({
      message: '渠道选择已变化，请刷新后重试',
    });
  });
});
