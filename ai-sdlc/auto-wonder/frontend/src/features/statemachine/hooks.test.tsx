import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import * as hooks from './hooks';

beforeEach(() => { vi.restoreAllMocks(); });
afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it.each([
  ['template create', 'post', '', hooks.useCreateTemplate, { workType: 'REQ', name: '<b>模板</b>' }, { workType: 'REQ', name: '<b>模板</b>' }, 'Template created', ['status-templates', 'REQ']],
  ['template update', 'put', '/9', hooks.useUpdateTemplate, { id: 9, data: { isDefault: true } }, { isDefault: true }, 'Saved', ['status-templates', 'REQ']],
  ['template delete', 'delete', '/9', hooks.useDeleteTemplate, 9, undefined, 'Deleted', ['status-templates', 'REQ']],
  ['node create', 'post', '/9/nodes', hooks.useCreateNode, { code: 'constructor', name: '<i>节点</i>', category: 'IN_PROGRESS', sort: 3 }, { code: 'constructor', name: '<i>节点</i>', category: 'IN_PROGRESS', sort: 3 }, 'Node added', ['status-template-detail', 9]],
  ['node update', 'put', '/9/nodes/11', hooks.useUpdateNode, { nodeId: 11, data: { name: '自定义' } }, { name: '自定义' }, 'Saved', ['status-template-detail', 9]],
  ['node delete', 'delete', '/9/nodes/11', hooks.useDeleteNode, 11, undefined, 'Node deleted', ['status-template-detail', 9]],
  ['transition create', 'post', '/9/transitions', hooks.useCreateTransition, { fromNodeId: 11, toNodeId: 12, name: '流转' }, { fromNodeId: 11, toNodeId: 12, name: '流转' }, 'Transition added', ['status-template-detail', 9]],
  ['transition update', 'put', '/9/transitions/8', hooks.useUpdateTransition, { tid: 8, data: { fromNodeId: 11, toNodeId: 12, name: '流转' } }, { fromNodeId: 11, toNodeId: 12, name: '流转' }, 'Saved', ['status-template-detail', 9]],
  ['transition delete', 'delete', '/9/transitions/8', hooks.useDeleteTransition, 8, undefined, 'Transition deleted', ['status-template-detail', 9]],
] as const)('%s resolves completion copy in the new locale with unchanged payload/cache keys', async (_name, method, path, useMutation, input, expectedBody, completion, queryKey) => {
  let release!: () => void;
  const deferred = new Promise<void>(resolve => { release = resolve; });
  let requested = false;
  let body: unknown;
  server.use(http[method](`/api/status-templates${path}`, async ({ request }) => {
    body = method === 'delete' ? undefined : await request.json();
    requested = true;
    await deferred;
    return HttpResponse.json({ success: true, data: { id: 9 } });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  const success = vi.spyOn(message, 'success').mockImplementation(() => (() => {}) as ReturnType<typeof message.success>);
  // The table spans existing hook signatures; keep the real hooks/API and HTTP boundary.
  const { result } = renderHook(() => (useMutation as (arg: never) => ReturnType<typeof hooks.useCreateTemplate>)(
    (_name.startsWith('template') ? 'REQ' : 9) as never,
  ), { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
  act(() => { result.current.mutate(input as never); });
  await waitFor(() => expect(requested).toBe(true));
  expect(body).toEqual(expectedBody);
  await act(async () => { await i18n.changeLanguage('en-US'); release(); });
  await waitFor(() => expect(success).toHaveBeenCalledWith(completion));
  expect(invalidate).toHaveBeenCalledWith({ queryKey });
});
