import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import { updateStep, type UpdateStepParams } from './api';

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
