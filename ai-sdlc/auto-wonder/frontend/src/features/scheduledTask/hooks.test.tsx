import { afterEach, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useDeleteScheduledTask, useDeleteScheduledTaskDocument, useRunScheduledTaskNow, useUploadScheduledTaskDocuments } from './hooks';

afterEach(async () => { vi.restoreAllMocks(); await act(() => i18n.changeLanguage('zh-CN')); });

it('uses the current language for all four deferred mutation completions and retains payload/query contracts', async () => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  const requests: { url: string; body?: unknown }[] = [];
  server.use(
    http.post('/api/scheduled-tasks/9/run-now', async ({ request }) => { requests.push({ url: request.url, body: await request.json() }); await pending; return HttpResponse.json({ success: true, code: '0', data: { id: 7 } }); }),
    http.delete('/api/scheduled-tasks/9', async ({ request }) => { requests.push({ url: request.url }); await pending; return HttpResponse.json({ success: true, code: '0', data: null }); }),
    http.post('/api/scheduled-tasks/9/documents', async ({ request }) => { requests.push({ url: request.url }); await pending; return HttpResponse.json({ success: true, code: '0', data: [] }); }),
    http.delete('/api/scheduled-tasks/9/documents/77', async ({ request }) => { requests.push({ url: request.url }); await pending; return HttpResponse.json({ success: true, code: '0', data: null }); }),
  );
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  const { result } = renderHook(() => ({ run: useRunScheduledTaskNow(), remove: useDeleteScheduledTask(), upload: useUploadScheduledTaskDocuments(), document: useDeleteScheduledTaskDocument() }), { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
  act(() => {
    result.current.run.mutate({ id: 9, version: 3, requestId: 'canonical-request-id' });
    result.current.remove.mutate({ id: 9, version: 3 });
    result.current.upload.mutate({ id: 9, files: [new File(['raw'], '原始.md', { type: 'text/markdown' })] });
    result.current.document.mutate({ id: 9, artifactId: 77 });
  });
  await waitFor(() => expect(requests).toHaveLength(4));
  expect(success).not.toHaveBeenCalled();
  await act(() => i18n.changeLanguage('en-US'));
  act(() => release());
  await waitFor(() => expect(success).toHaveBeenCalledTimes(4));
  expect(success.mock.calls.map(([text]) => text).sort()).toEqual(['Attachment deleted', 'Requirement documents uploaded', 'Run created', 'Scheduled task deleted'].sort());
  expect(requests.find(request => request.url.endsWith('/run-now'))?.body).toEqual({ version: 3, requestId: 'canonical-request-id' });
  expect(requests.some(request => new URL(request.url).searchParams.get('version') === '3')).toBe(true);
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['scheduled-tasks', 9, 'documents'] });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['scheduled-task-runs', 9] });
});
