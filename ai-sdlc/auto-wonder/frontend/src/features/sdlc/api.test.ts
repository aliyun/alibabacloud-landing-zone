import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import { listAllSdlcTemplates, listAllSdlcTemplatesFlat, updateStep, type UpdateStepParams } from './api';

const stepBody = {
  id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis',
  instructionMd: '理解需求', checklistJson: null, gatePolicyJson: null,
  required: true, timeoutSeconds: null, retryBudget: null,
};

function mockPut(capture: (body: unknown, path: string) => void) {
  server.use(
    http.put('/api/sdlcs/:sdlcId/steps/:stepId', async ({ request }) => {
      capture(await request.json(), new URL(request.url).pathname);
      return HttpResponse.json({
        success: true, code: '0', message: '', data: stepBody, traceId: null,
      });
    }),
  );
}

describe('updateStep', () => {
  it('forwards handler and transition fields that CreateStepParams never carried', async () => {
    let capturedBody: unknown = null;
    let capturedPath: string | null = null;
    mockPut((body, path) => { capturedBody = body; capturedPath = path; });

    const params: UpdateStepParams = {
      name: '需求分析',
      code: 'STEP_1',
      handlerType: 'AGENT',
      handlerRoleRef: 'AW_CR',
      statusOnEnterCode: 'aone_172915',
      onSuccess: '{"to":"STEP_2"}',
      onFail: '{"to":"STOP"}',
    };
    const result = await updateStep(1, 10, params);

    expect(capturedPath).toBe('/api/sdlcs/1/steps/10');
    expect(capturedBody).toEqual(params);
    expect(result.name).toBe('需求分析');
  });

  it('omits untouched fields so the backend keeps their current values', async () => {
    let capturedBody: unknown = null;
    mockPut((body) => { capturedBody = body; });

    await updateStep(1, 10, { name: 'renamed' });

    // A body that spells out undefined or null for the untouched columns would make the
    // server overwrite them instead of preserving them.
    expect(Object.keys(capturedBody as Record<string, unknown>)).toEqual(['name']);
    expect(capturedBody).not.toHaveProperty('handlerRoleRef');
    expect(capturedBody).not.toHaveProperty('statusOnEnterCode');
    expect(capturedBody).not.toHaveProperty('onSuccess');
    expect(capturedBody).not.toHaveProperty('onFail');
  });

  it('sends an empty string when a nullable field is explicitly cleared', async () => {
    let capturedBody: unknown = null;
    mockPut((body) => { capturedBody = body; });

    await updateStep(1, 10, { handlerRoleRef: '', timeoutSeconds: null });

    expect(capturedBody).toEqual({ handlerRoleRef: '', timeoutSeconds: null });
  });
});

function listRow(id: string, name = `sdlc-${id}`) {
  return {
    id, name, description: '', workType: null, status: 'ENABLED', isDefault: 0,
    entryStepId: null, version: 1, gmtCreate: '2026-10-28', steps: [],
  };
}

describe('listAllSdlcTemplates', () => {
  it('walks every page and deduplicates rows that shift across page boundaries', async () => {
    const requested: number[] = [];
    server.use(http.get('/api/sdlcs/page', ({ request }) => {
      const params = new URL(request.url).searchParams;
      const page = Number(params.get('page'));
      requested.push(page);
      const list = page === 1 ? [listRow('1'), listRow('2')] : [listRow('2'), listRow('3')];
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list, total: 3, pageNum: page, pageSize: 100 },
      });
    }));

    const result = await listAllSdlcTemplates({ squadIds: [7] });

    expect(requested).toEqual([1, 2]);
    expect(result.total).toBe(3);
    expect(result.items.map((item) => String(item.id))).toEqual(['1', '2', '3']);
  });

  it('stops when a page adds no new row even if the reported total is higher', async () => {
    let calls = 0;
    server.use(http.get('/api/sdlcs/page', () => {
      calls += 1;
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [listRow('1'), listRow('2')], total: 5, pageNum: 1, pageSize: 100 },
      });
    }));

    const result = await listAllSdlcTemplates();

    expect(calls).toBe(2);
    expect(result.items).toHaveLength(2);
  });

  it('requests a single page when it already covers the total', async () => {
    let calls = 0;
    server.use(http.get('/api/sdlcs/page', () => {
      calls += 1;
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [listRow('1')], total: 1, pageNum: 1, pageSize: 100 },
      });
    }));

    const result = await listAllSdlcTemplates();

    expect(calls).toBe(1);
    expect(result.total).toBe(1);
  });
});


describe('listAllSdlcTemplatesFlat', () => {
  it('returns the plain list shape in a single request when the page is not full', async () => {
    let calls = 0;
    server.use(http.get('/api/sdlcs', () => {
      calls += 1;
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [listRow('1'), listRow('2')] });
    }));

    const items = await listAllSdlcTemplatesFlat();

    expect(calls).toBe(1);
    expect(items.map((item) => String(item.id))).toEqual(['1', '2']);
  });

  it('accepts a wrapped list payload from the plain endpoint', async () => {
    let calls = 0;
    server.use(http.get('/api/sdlcs', () => {
      calls += 1;
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [listRow('9')], total: 1 },
      });
    }));

    const items = await listAllSdlcTemplatesFlat();

    expect(calls).toBe(1);
    expect(items.map((item) => String(item.id))).toEqual(['9']);
  });

  it('walks further pages so a workflow past the first 100 stays selectable', async () => {
    const requested: number[] = [];
    const firstPage = Array.from({ length: 100 }, (_, index) => listRow(String(index + 1)));
    server.use(http.get('/api/sdlcs', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'));
      requested.push(page);
      const list = page === 1 ? firstPage : [listRow('101')];
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list, total: 101 } });
    }));

    const items = await listAllSdlcTemplatesFlat();

    expect(requested).toEqual([1, 2]);
    expect(items).toHaveLength(101);
    expect(String(items.at(-1)?.id)).toBe('101');
  });

  it('stops when a full page repeats rows instead of looping forever', async () => {
    let calls = 0;
    const repeated = Array.from({ length: 100 }, (_, index) => listRow(String(index + 1)));
    server.use(http.get('/api/sdlcs', () => {
      calls += 1;
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: repeated });
    }));

    const items = await listAllSdlcTemplatesFlat();

    expect(calls).toBe(2);
    expect(items).toHaveLength(100);
  });
});
