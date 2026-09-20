import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import {
  getRuntimeAutoUpdate, updateAllExecutors, updateExecutor,
} from './api';

const UPDATE_VO = {
  taskId: 91, requestId: 'req-1', status: 'PENDING', currentVersion: '0.2.155',
  targetVersion: '0.2.160', attemptCount: 0, maxAttempts: 3, lastError: null,
  source: 'MANUAL', requestedAt: '2026-09-11T08:00:00Z', nextAttemptAt: null, completedAt: null,
};

describe('updateExecutor', () => {
  it('posts to the per-executor endpoint and returns the scheduled task', async () => {
    let pathname: string | null = null;
    server.use(http.post('/api/executors/:id/update', ({ request }) => {
      pathname = new URL(request.url).pathname;
      return HttpResponse.json({ success: true, code: '0', message: '', data: UPDATE_VO, traceId: null });
    }));

    const result = await updateExecutor(7);

    expect(pathname).toBe('/api/executors/7/update');
    // 目标版本由服务端的全平台配置决定，调用方一个字段都不传
    expect(result).toMatchObject({ taskId: 91, targetVersion: '0.2.160', status: 'PENDING' });
  });
});

describe('updateAllExecutors', () => {
  function captureParams(capture: (params: URLSearchParams) => void) {
    server.use(http.post('/api/executors/update-all', ({ request }) => {
      capture(new URL(request.url).searchParams);
      return HttpResponse.json({
        success: true, code: '0', message: '',
        data: { targetVersion: '0.2.160', total: 2, scheduled: 1, alreadyUpToDate: 1, skipped: [] },
        traceId: null,
      });
    }));
  }

  it('omits squadIds entirely when the operator has no squad filter', async () => {
    let params = new URLSearchParams('unset');
    captureParams((value) => { params = value; });

    await updateAllExecutors();

    expect(Array.from(params.keys())).toEqual([]);
  });

  it('joins the squad filter with commas so Spring can bind it to a List', async () => {
    let params = new URLSearchParams('unset');
    captureParams((value) => { params = value; });

    await updateAllExecutors([7, 8]);

    // axios 默认的 squadIds[]=7&squadIds[]=8 无法被 @RequestParam List<Long> 绑定
    expect(Array.from(params.keys())).toEqual(['squadIds']);
    expect(params.get('squadIds')).toBe('7,8');
  });

  it('reports the skipped executors instead of failing the whole batch', async () => {
    server.use(http.post('/api/executors/update-all', () => HttpResponse.json({
      success: true, code: '0', message: '',
      data: {
        targetVersion: '0.2.160', total: 3, scheduled: 1, alreadyUpToDate: 1,
        skipped: [{ executorId: 12, executorName: 'busy-runner', reason: '执行器正在派单中' }],
      },
      traceId: null,
    })));

    const result = await updateAllExecutors([]);

    expect(result.skipped).toEqual([{ executorId: 12, executorName: 'busy-runner', reason: '执行器正在派单中' }]);
    expect(result.scheduled).toBe(1);
  });
});

describe('runtime auto update', () => {
  it('reads the platform switch', async () => {
    let pathname: string | null = null;
    server.use(http.get('/api/platform/runtime-auto-update', ({ request }) => {
      pathname = new URL(request.url).pathname;
      return HttpResponse.json({
        success: true, code: '0', message: '',
        data: { executorAutoUpdateEnabled: true, targetVersion: '0.2.160' },
        traceId: null,
      });
    }));

    const result = await getRuntimeAutoUpdate();

    expect(pathname).toBe('/api/platform/runtime-auto-update');
    expect(result).toEqual({ executorAutoUpdateEnabled: true, targetVersion: '0.2.160' });
  });
});
