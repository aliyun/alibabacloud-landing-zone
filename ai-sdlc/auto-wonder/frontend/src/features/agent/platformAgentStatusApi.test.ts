import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ApiError } from '@/shared/types/common';
import {
  getPlatformAgentStatus,
  platformAgentStatusQueryKey,
} from './platformAgentStatusApi';

describe('platformAgentStatusQueryKey', () => {
  it('keys on workspace id under a shared prefix', () => {
    expect(platformAgentStatusQueryKey(7)).toEqual(['platform-agent', 'status', 7]);
    expect(platformAgentStatusQueryKey(null)).toEqual(['platform-agent', 'status', null]);
  });
});

describe('getPlatformAgentStatus', () => {
  it('GETs the status endpoint and unwraps the envelope', async () => {
    let capturedPath: string | null = null;
    server.use(
      http.get('/api/platform-agent/status', ({ request }) => {
        capturedPath = new URL(request.url).pathname;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { state: 'OFFLINE', agentId: 11, executorCount: 2, onlineExecutorCount: 0 },
          traceId: null,
        });
      }),
    );

    const status = await getPlatformAgentStatus();

    expect(capturedPath).toBe('/api/platform-agent/status');
    expect(status).toEqual({ state: 'OFFLINE', agentId: 11, executorCount: 2, onlineExecutorCount: 0 });
  });

  it('surfaces a business error as an ApiError carrying the backend code', async () => {
    server.use(
      http.get('/api/platform-agent/status', () => HttpResponse.json({
        success: false,
        code: '12030',
        message: '不是该工作空间成员',
        data: null,
        traceId: 'trace-err',
      })),
    );

    const error = await getPlatformAgentStatus().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('12030');
  });
});
