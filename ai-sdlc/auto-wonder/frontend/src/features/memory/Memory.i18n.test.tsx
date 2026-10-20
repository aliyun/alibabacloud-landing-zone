import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { MemoryListPage } from './MemoryListPage';
import { MemoryTopicEditor } from './MemoryTopicEditor';
import { MemoryDocumentEditor } from './MemoryDocumentEditor';
import { MemoryImportSourcesPanel } from './MemoryImportSourcesPanel';
import { MemoryImportPage } from './MemoryImportPage';
import { MemoryAclPanel } from './MemoryAclPanel';
import type { MemoryDocument, MemoryOwner, MemoryTopicInput, MemoryMutation } from './api';

const raw = '<b>组织共享记忆</b>';
const owner: MemoryOwner = { scope: 'AGENT', ownerRef: 42, name: raw, squadNames: ['<i>小队</i>'], storeId: 11, currentRevision: 8, canCreate: true };
const document: MemoryDocument = { id: 2, storeId: 11, path: 'feedback_原文.md', title: raw, description: '<i>适用说明</i>', body: '<script>原文</script>', contentMd: '原始文件', memoryType: 'feedback', contentSha256: 'sha', byteSize: 1234, modifiedAt: '', version: 7 };
async function language(locale: 'zh-CN' | 'en-US') { await act(async () => { await i18n.changeLanguage(locale); }); }
function routed(element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const router = createMemoryRouter([{ path: '/memories', element }], { initialEntries: ['/memories'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return client;
}
function fixture() {
  const calls = { directory: 0, documents: 0, imports: 0 };
  server.use(
    http.get('/api/memory-stores/directory', () => { calls.directory++; return HttpResponse.json({ success: true, data: { owners: [owner, { ...owner, scope: 'ORG', ownerRef: 0, name: '组织共享记忆', storeId: undefined }], migration: { migrated: 1, pending: 2 } } }); }),
    http.get('/api/memory-stores/11/documents', () => { calls.documents++; return HttpResponse.json({ success: true, data: [document] }); }),
    http.get('/api/memory-stores/11/history', () => HttpResponse.json({ success: true, data: [] })),
    http.get('/api/memory-stores/11/acls', () => HttpResponse.json({ success: true, data: [] })),
    http.get('/api/memory-imports', () => { calls.imports++; return HttpResponse.json({ success: true, data: [] }); }),
  );
  return calls;
}
beforeEach(async () => { useAuthStore.setState({ accessLevel: 'ADMIN' }); await language('zh-CN'); });
afterEach(async () => { cleanup(); vi.restoreAllMocks(); useAuthStore.getState().clear(); localStorage.clear(); await language('zh-CN'); });

describe('memory live locale', () => {
  it.each(['topic', 'index'])('keeps invalid %s saves inline without closing or invoking the caller', async kind => {
    const save = vi.fn(); const cancel = vi.fn();
    routed(kind === 'topic'
      ? <MemoryTopicEditor open owners={[owner]} initialOwner={owner} document={document} saving={false} onCancel={cancel} onSave={save} />
      : <MemoryDocumentEditor open document={document} writable saving={false} onCancel={cancel} onSave={save} />);
    const field = screen.getByLabelText(kind === 'topic' ? '标题' : '路径');
    fireEvent.change(field, { target: { value: kind === 'topic' ? '' : '../unsafe.md' } });
    await language('en-US');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(kind === 'topic' ? 'Enter a title' : 'Enter a safe relative Markdown path')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText(kind === 'topic' ? 'Body' : 'Content')).toHaveValue(kind === 'topic' ? '<script>原文</script>' : '原始文件');
    expect(save).not.toHaveBeenCalled(); expect(cancel).not.toHaveBeenCalled();
  });
  it('updates the cached routed viewer and fixed owner while preserving literal memory data', async () => {
    const calls = fixture(); routed(<MemoryListPage />);
    await userEvent.click(await screen.findByRole('button', { name: raw }));
    const dialog = screen.getByRole('dialog', { name: raw });
    expect(within(dialog).getByRole('button', { name: '关闭' })).toBeInTheDocument();
    await language('en-US');
    expect(screen.getByRole('heading', { name: 'Memory' })).toBeInTheDocument();
    expect(screen.getByText('Organization shared memory')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Memory content (1)' })).toBeInTheDocument();
    expect(within(dialog).getByText(/Feedback and agreements/)).toBeInTheDocument();
    expect(within(dialog).getByText('<script>原文</script>')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(dialog.querySelector('script')).toBeNull();
    expect(calls).toMatchObject({ directory: 1, documents: 1 });
    await language('zh-CN');
    expect(screen.getByRole('dialog', { name: raw })).toBe(dialog);
    expect(within(dialog).getByText(/反馈与约定/)).toBeInTheDocument();
  });
  it('refreshes only displayed topic errors and keeps drafts, semantic codes, versions and retry key', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    routed(<MemoryTopicEditor open owners={[owner]} initialOwner={owner} document={document} saving={false} onCancel={vi.fn()} onSave={save} />);
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument();
    const title = screen.getByLabelText('标题');
    fireEvent.change(title, { target: { value: '' } });
    expect(await screen.findByText('请输入标题')).toBeInTheDocument();
    await language('en-US');
    expect(await screen.findByText('Enter a title')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toBe(title);
    expect(screen.getByLabelText('Body')).toHaveValue('<script>原文</script>');
    expect(screen.queryByText('Enter a body')).not.toBeInTheDocument();
    fireEvent.change(title, { target: { value: raw } });
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    await language('zh-CN'); await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(save.mock.calls[0][1]).toMatchObject({ type: 'feedback', path: 'feedback_原文.md', expectedVersion: 7, title: raw, description: '<i>适用说明</i>', contentMd: '<script>原文</script>' });
    expect(save.mock.calls[1][1].idempotencyKey).toBe(save.mock.calls[0][1].idempotencyKey);
    expect(save.mock.calls[0][0]).toEqual(owner);
  });
  it('keeps unsafe-path errors reactive and advisory UTF-8 boundaries savable', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    routed(<MemoryDocumentEditor open document={{ ...document, path: 'MEMORY.md', contentMd: Array(201).fill('一').join('\n') }} writable saving={false} onCancel={vi.fn()} onSave={save} />);
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument();
    const path = screen.getByLabelText('路径');
    fireEvent.change(path, { target: { value: '../unsafe.md' } });
    expect(await screen.findByText('请输入安全的相对 Markdown 路径')).toBeInTheDocument();
    await language('en-US');
    expect(await screen.findByText('Enter a safe relative Markdown path')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(screen.getByLabelText('Path')).toBe(path);
    fireEvent.change(path, { target: { value: 'MEMORY.md' } });
    expect(await screen.findByText(/You can still save/)).toBeInTheDocument();
    expect(screen.getByText('201 lines · 803 bytes')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save.mock.calls[0][0]).toMatchObject({ operation: 'UPDATE', path: 'MEMORY.md', newPath: 'MEMORY.md', expectedVersion: 7, contentMd: Array(201).fill('一').join('\n') });
  });
  it('refreshes ACL errors without changing raw subject references or ADMIN payload codes', async () => {
    fixture(); let payload: unknown;
    server.use(http.put('/api/memory-stores/11/acls', async ({ request }) => { payload = await request.json(); return HttpResponse.json({ success: true, data: null }); }));
    routed(<MemoryAclPanel storeId={11} administrator />);
    await userEvent.click(screen.getByRole('button', { name: '添加访问权限' }));
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument();
    const ref = screen.getByLabelText('对象编号');
    fireEvent.change(ref, { target: { value: 'raw' } }); fireEvent.change(ref, { target: { value: '' } });
    expect(await screen.findByText('请输入对象编号')).toBeInTheDocument();
    await language('en-US');
    expect(await screen.findByText('Enter a subject ID')).toBeInTheDocument();
    expect(screen.getByLabelText('Subject ID')).toBe(ref);
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    fireEvent.change(ref, { target: { value: '<b>ALL_AGENTS</b>' } });
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(payload).toEqual({ subjectType: 'AGENT', permission: 'READ', subjectRef: '<b>ALL_AGENTS</b>' }));
  });
});

// Supplemental preservation coverage, added after the representative RED above.
it('renders only explicit parse-warning origin and keeps unknown/absent same-looking descriptions raw', async () => {
  const calls = fixture();
  const warning = '元信息无法解析，可在文件详情中查看原文';
  server.use(http.get('/api/memory-stores/11/documents', () => { calls.documents++; return HttpResponse.json({ success: true, data: [
    { ...document, id: 1, title: 'marked', path: 'marked.md', description: warning, descriptionKind: 'METADATA_PARSE_WARNING' },
    { ...document, id: 2, title: 'authored', path: 'authored.md', description: warning },
    { ...document, id: 3, title: 'unknown', path: 'unknown.md', description: warning, descriptionKind: 'constructor', memoryType: 'constructor' },
  ] }); }));
  const client = routed(<MemoryListPage />);
  await screen.findByRole('button', { name: 'marked' });
  const row = (name: string) => screen.getByRole('button', { name }).closest('li')!;
  expect(row('marked')).toHaveTextContent(warning); expect(row('authored')).toHaveTextContent(warning);
  await language('en-US');
  expect(row('marked')).toHaveTextContent('Metadata could not be parsed. View the original text in the file details.');
  expect(row('authored')).toHaveTextContent(warning); expect(row('unknown')).toHaveTextContent(warning);
  await userEvent.click(screen.getByRole('button', { name: 'unknown' }));
  const dialog = screen.getByRole('dialog', { name: 'unknown' });
  expect(dialog).toHaveTextContent('unknown.md · File version 7 · constructor');
  expect(within(dialog).getByText('<script>原文</script>')).toBeInTheDocument();
  expect(calls.documents).toBe(1); expect(calls.directory).toBe(1);
  const cached = client.getQueryData<MemoryDocument[]>(client.getQueryCache().findAll({ queryKey: ['memory-stores'] }).find(q => q.queryKey.includes('documents') && q.state.data !== undefined)!.queryKey)!;
  expect(cached[0].description).toBe(warning); expect(cached[0].contentMd).toBe('原始文件');
  await language('zh-CN'); expect(row('marked')).toHaveTextContent(warning); expect(row('unknown')).toHaveTextContent(warning);
});

it.each(['topic', 'index'])('uses the completion locale for a delayed %s save and preserves its request', async (kind) => {
  fixture(); let release!: () => void; let payload!: MemoryTopicInput | MemoryMutation;
  const pending = new Promise<void>(resolve => { release = resolve; });
  server.use(
    http.get('/api/memory-stores/11/documents', () => HttpResponse.json({ success: true, data: [document, { ...document, id: 3, path: 'MEMORY.md', title: undefined }] })),
    http.put('/api/memory-stores/11/topics', async ({ request }) => { payload = await request.json() as MemoryTopicInput; await pending; return HttpResponse.json({ success: true, data: document }); }),
    http.post('/api/memory-stores/11/mutations', async ({ request }) => { payload = await request.json() as MemoryMutation; await pending; return HttpResponse.json({ success: true, data: document }); }),
  );
  const success = vi.spyOn((await import('antd')).message, 'success');
  routed(<MemoryListPage />);
  await userEvent.click(await screen.findByRole('button', { name: kind === 'topic' ? '编辑' : '编辑索引' }));
  const dialog = screen.getByRole('dialog'); expect(within(dialog).getByRole('button', { name: '关闭' })).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: /保\s*存/ }));
  await waitFor(() => expect(payload).toBeTruthy());
  await language('en-US'); expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument();
  await act(async () => { release(); });
  await waitFor(() => expect(success).toHaveBeenCalledWith(kind === 'topic' ? 'Memory saved' : 'Index saved'));
  expect(payload).toMatchObject({ path: kind === 'topic' ? 'feedback_原文.md' : 'MEMORY.md', expectedVersion: 7 });
  expect(payload.idempotencyKey).toBeTruthy();
});

it('updates independently rendered import statuses, plural counts and dates without changing queries or commands', async () => {
  let imports = 0; const receipts: number[] = []; let payload: unknown;
  const source = { id: 21, agentId: 42, targetStoreId: 11, providerFamily: raw, logicalPath: '原文.md', installationFingerprint: 'raw', status: 'ACTIVE', lastSeenAt: '2026-09-17T01:02:00Z', version: 3 };
  server.use(
    http.get('/api/memory-imports', () => { imports++; return HttpResponse.json({ success: true, data: [source, { ...source, id: 22, status: 'MISSING' }, { ...source, id: 23, status: 'constructor' }, { ...source, id: 24, agentId: 99, logicalPath: 'hidden.md' }] }); }),
    http.get('/api/memory-imports/:id/receipts', ({ params }) => { receipts.push(Number(params.id)); return HttpResponse.json({ success: true, data: [{ id: 1, sourceId: Number(params.id), sanitizedContentSha256: 'sha', outcome: 'SUCCEEDED', redactionCount: 0, gmtCreate: '' }] }); }),
    http.put('/api/memory-imports/21/status', async ({ request }) => { payload = await request.json(); return HttpResponse.json({ success: true, data: null }); }),
  );
  routed(<MemoryImportSourcesPanel administrator agentId={42} />);
  await screen.findByText('可导入'); await waitFor(() => expect(receipts.length).toBe(2));
  await language('en-US');
  expect(screen.getByText('Ready to import')).toBeInTheDocument(); expect(screen.getByText('constructor')).toBeInTheDocument();
  expect(screen.getAllByText(/1 successful import in recent records/)).toHaveLength(2);
  expect(screen.getByText('Local file not found (1)')).toBeInTheDocument();
  expect(screen.queryByText(/hidden.md/)).not.toBeInTheDocument();
  const date = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(source.lastSeenAt));
  expect(screen.getAllByText(new RegExp(date.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))).toHaveLength(2);
  await userEvent.click(screen.getByText('Local file not found (1)'));
  expect(await screen.findByText('Local file not found')).toBeInTheDocument();
  expect(screen.getByText(/0 successful imports in recent records/)).toBeInTheDocument();
  expect(receipts).not.toContain(22); expect(imports).toBe(1);
  const active = screen.getByText('Ready to import').closest('li')!;
  await userEvent.click(within(active).getByRole('button', { name: 'Pause' }));
  await waitFor(() => expect(payload).toEqual({ status: 'PAUSED', version: 3 }));
  await language('zh-CN'); expect(screen.getByText('可导入')).toBeInTheDocument();
});

it('localizes the existing unrouted AI import page without changing its return navigation', async () => {
  routed(<MemoryImportPage />);
  expect(screen.getByRole('button', { name: '返回' })).toBeInTheDocument();
  await language('en-US'); expect(screen.getByText('AI memory import')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
  await language('zh-CN'); expect(screen.getByText('AI 记忆导入')).toBeInTheDocument();
});

it('retains selected owner, topic and history pagination on the cached route across languages', async () => {
  fixture(); let documentCalls = 0; let historyCalls = 0;
  server.use(
    http.get('/api/memory-stores/11/documents', () => { documentCalls++; return HttpResponse.json({ success: true, data: Array.from({ length: 11 }, (_, i) => ({ ...document, id: i + 1, path: `topic_${String(i).padStart(2, '0')}.md`, title: `raw${i}` })) }); }),
    http.get('/api/memory-stores/11/history', () => { historyCalls++; return HttpResponse.json({ success: true, data: Array.from({ length: 11 }, (_, i) => ({ id: i + 1, operation: i === 10 ? 'constructor' : 'UPDATE', path: `history_${i}.md`, storeRevision: i + 1 })) }); }),
  );
  routed(<MemoryListPage />); await screen.findByRole('button', { name: 'raw0' });
  await userEvent.click(screen.getByTitle('2')); expect(await screen.findByRole('button', { name: 'raw10' })).toBeInTheDocument();
  await language('en-US'); expect(screen.getByRole('button', { name: 'raw10' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'raw0' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('tab', { name: 'Change history' }));
  const pages = screen.getAllByTitle('2'); await userEvent.click(pages[pages.length - 1]); expect(await screen.findByText('constructor · history_10.md')).toBeInTheDocument();
  await language('zh-CN'); expect(screen.getByText('constructor · history_10.md')).toBeInTheDocument();
  expect(documentCalls).toBe(1); expect(historyCalls).toBe(1);
});

it('renders the ORG owner option from scope while retaining same-looking Squad names', async () => {
  const organization: MemoryOwner = { ...owner, scope: 'ORG', ownerRef: 0, name: 'stored name must stay raw', squadNames: [] };
  routed(<MemoryTopicEditor open owners={[organization]} initialOwner={organization} saving={false} onCancel={vi.fn()} onSave={vi.fn()} />);
  expect(screen.getByText('组织共享记忆')).toBeInTheDocument();
  await language('en-US'); expect(screen.getByText('Organization shared memory')).toBeInTheDocument();
  expect(organization.name).toBe('stored name must stay raw');
  cleanup();
  const squad: MemoryOwner = { ...organization, scope: 'SQUAD', name: '组织共享记忆' };
  routed(<MemoryTopicEditor open owners={[squad]} initialOwner={squad} saving={false} onCancel={vi.fn()} onSave={vi.fn()} />);
  expect(screen.getByText('组织共享记忆')).toBeInTheDocument();
  expect(screen.queryByText('Organization shared memory')).not.toBeInTheDocument();
  await language('zh-CN'); expect(screen.getByText('组织共享记忆')).toBeInTheDocument();
});
