import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemDetailPage } from './WorkitemDetailPage';
import { SharedArtifactPage } from './SharedArtifactPage';
import { AssignHumanModal } from './components/AssignHumanModal';
import { CommentInteractionCard } from './components/CommentInteractionCard';
import { activityText } from './components/commentActivity';
import type { RuntimeTraceEvent } from '@/shared/types/workitem';

const name = '需求 <strong>Alice</strong>';
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
const item = { id: 42, title: name, contentMd: '业务原文', workType: 'REQ', priority: 1, templateId: 1, statusNodeId: 1, statusName: name, statusCategory: 'IN_PROGRESS', assigneeType: 'HUMAN', assigneeRef: 7, assigneeDisplayName: name, creatorDisplayName: name, version: 1, gmtCreate: '2026-07-01T10:00:00Z' };
const human = { userId: 7, targetType: 'HUMAN', name, displayId: 'H7', role: 'HUMAN', roleName: '自定义角色', isAgent: false, online: false };
function mount(element: React.ReactNode, path = '/workitems/42', route = '/workitems/:id') {
  const router = createMemoryRouter([{ path: route, element }], { initialEntries: [path] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return { ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>), router };
}
async function language(locale: 'zh-CN' | 'en-US') { await act(async () => { await i18n.changeLanguage(locale); }); }
beforeEach(async () => {
  vi.restoreAllMocks(); localStorage.clear(); useAuthStore.getState().clear();
  useAuthStore.setState({ accessLevel: 'READ_WRITE' }); await i18n.changeLanguage('zh-CN');
  server.use(
    http.get('/api/workitems/42', () => ok(item)),
    http.get('/api/workitems/42/unified-timeline', () => ok([{ id: 1, type: 'comment', authorId: 7, authorName: name, authorType: 'HUMAN', isAgent: false, content: '评论原文', gmtCreate: new Date().toISOString(), interactions: [{ guidanceId: 9, targetAgentId: 8, targetAgentName: name, status: 'APPLIED', replyContent: 'AI 回复原文' }] }])),
    http.get('/api/workitems/42/participants', () => ok([human])),
    http.get('/api/workitems/42/mention-candidates', () => ok([human])),
    http.get('/api/workitems/42/delivery-progress', () => ok({ steps: [], agents: [] })),
    http.get('/api/workitems/42/dispatch-requests', () => ok([])),
    http.get('/api/workitems/42/clarification', () => ok({ contentMd: 'AI 澄清原文' })),
    http.get('/api/workitems/42/artifacts', () => ok([])),
    http.get('/api/workitems/42/requirement-documents', () => ok([])),
    http.get('/api/workitems/42/watchers', () => ok([human])),
    http.get('/api/workitems/42/recovery', () => ok({ closed: false, executions: [] })),
    http.get('/api/status-templates/1', () => ok({ nodes: [{ id: 1, name: '当前' }, { id: 2, name }], transitions: [{ fromNodeId: 1, toNodeId: 2 }] })),
  );
});
afterEach(async () => { cleanup(); vi.unstubAllGlobals(); await i18n.changeLanguage('zh-CN'); });

it('updates the actual cached detail route, retaining content and comment drafts and literal HTML-like names', async () => {
  let payload: unknown;
  server.use(http.put('/api/workitems/42/content', async ({ request }) => { payload = await request.json(); return ok(item); }));
  mount(<WorkitemDetailPage />); await screen.findByRole('heading', { name });
  fireEvent.click(within(screen.getByTestId('workitem-content-section')).getByRole('button', { name: /编辑/ }));
  const title = screen.getByLabelText('工单标题'), body = screen.getByLabelText('工单正文');
  const comment = screen.getByPlaceholderText('输入评论，键入 @ 选择成员...');
  fireEvent.change(title, { target: { value: name + ' draft' } }); fireEvent.change(body, { target: { value: '正文 <em>draft</em>' } }); fireEvent.change(comment, { target: { value: '评论 draft' } });
  await language('en-US');
  expect(screen.getByRole('button', { name: /Start delivery/ })).toBeInTheDocument();
  expect(screen.getByText('Comments & timeline')).toBeInTheDocument();
  expect(screen.getByText('Clarification materials (AI generated)')).toBeInTheDocument();
  expect(screen.getByLabelText('Work item title')).toBe(title); expect(screen.getByLabelText('Work item body')).toBe(body);
  expect(screen.getByPlaceholderText('Write a comment; type @ to choose a member…')).toBe(comment);
  expect(title).toHaveValue(name + ' draft'); expect(body).toHaveValue('正文 <em>draft</em>'); expect(comment).toHaveValue('评论 draft');
  expect(screen.getByText('AI 回复原文')).toBeInTheDocument(); expect(screen.getByText('AI 澄清原文')).toBeInTheDocument();
  expect(screen.getByTestId('comment-interaction-reply')).toHaveTextContent(name + ' replied to this question');
  expect(screen.getByTestId('comment-interaction-reply').querySelector('strong strong')).toBeNull();
  await language('zh-CN'); expect(screen.getByLabelText('工单标题')).toBe(title); await language('en-US');
  await userEvent.click(within(screen.getByTestId('workitem-content-section')).getByRole('button', { name: /Save/ }));
  await waitFor(() => expect(payload).toEqual({ title: name + ' draft', contentMd: '正文 <em>draft</em>' })); expect(comment).toHaveValue('评论 draft');
});
it('updates transition choices and submits unchanged authored status-node identity', async () => {
  let payload: unknown;
  server.use(http.post('/api/workitems/42/transition', async ({ request }) => { payload = await request.json(); return ok(item); }));
  mount(<WorkitemDetailPage />); await screen.findByRole('heading', { name }); fireEvent.click(screen.getByRole('button', { name: /流转状态/ }));
  await screen.findByRole('button', { name: `流转到${name}` }); expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument(); await language('en-US'); expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument(); expect(screen.getByText('Recommended transition')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: `Transition to ${name}` })); await waitFor(() => expect(payload).toMatchObject({ toNodeId: 2 }));
});
it('refreshes displayed assignment validation in place and preserves HUMAN assignment payload and names', async () => {
  let payload: unknown;
  server.use(http.put('/api/workitems/42/assignee', async ({ request }) => { payload = await request.json(); return ok(item); }));
  mount(<AssignHumanModal open workitemId={42} onClose={() => {}} />); const select = screen.getByLabelText('指派给');
  await userEvent.click(screen.getByRole('button', { name: /^指\s*派$/ })); await screen.findByText('请选择指派对象'); await language('en-US');
  expect(await screen.findByText('Choose an assignee.')).toBeInTheDocument(); expect(screen.getByLabelText('Assign to')).toBe(select);
  await userEvent.click(select); await userEvent.click(await screen.findByText(`${name} (H7)`));
  await language('zh-CN'); expect(screen.getByLabelText('指派给')).toBe(select); await language('en-US');
  await userEvent.click(screen.getByRole('button', { name: 'Assign' })); await waitFor(() => expect(payload).toEqual({ assigneeType: 'HUMAN', assigneeRef: 7 }));
});
it('localizes interaction states, duration and fixed runtime events while retaining raw diagnostics', async () => {
  mount(<CommentInteractionCard interaction={{ guidanceId: 1, targetAgentId: 8, targetAgentName: name, status: 'FAILED', error: 'Provider 原文 secret=private', repliedAt: '2026-07-01T10:00:03Z' }} createdAt="2026-07-01T10:00:00Z" />);
  expect(screen.getByText('回复失败')).toBeInTheDocument(); await language('en-US'); expect(screen.getByText('Reply failed')).toBeInTheDocument();
  expect(screen.getByText('Total time: 3 seconds')).toBeInTheDocument(); expect(screen.getByTestId('comment-provider-error')).toHaveTextContent('Provider 原文 secret=[REDACTED]');
  expect(activityText({ eventType: 'turn.started' } as RuntimeTraceEvent)).toBe('Processing the comment and current context');
  expect(activityText({ eventType: 'agent.progress', detail: { interaction: true, message: 'AI 原文' } } as RuntimeTraceEvent)).toBe('AI 原文');
});
it('switches the anonymous pending public route without restarting requests or rewriting the filename', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'PENDING', name: '需求 <Alice>.md', size: 1200 }))); vi.stubGlobal('fetch', fetchMock);
  mount(<SharedArtifactPage />, `/api/share/workitems/awshare_${'a'.repeat(43)}/requests/41`, '/api/share/workitems/:token/:kind/:id');
  await screen.findByText('文件正在生成'); await language('en-US'); expect(screen.getByText('File is being generated')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '需求 <Alice>.md' })).toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Switch language' })).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(1);
  await language('zh-CN'); expect(screen.getByText('文件正在生成')).toBeInTheDocument();
});
it('refreshes visible anonymous public errors without fetching or redirecting on language change', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response('', { status: 404 })); vi.stubGlobal('fetch', fetchMock);
  mount(<SharedArtifactPage />, `/api/share/workitems/awshare_${'a'.repeat(43)}/requests/41`, '/api/share/workitems/:token/:kind/:id');
  await screen.findByText('链接无效或产物已下线'); await language('en-US'); expect(screen.getByText('This link is invalid or the artifact is unavailable.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(1);
});
it('uses completion-time language for comment success without changing authored text', async () => {
  let finish!: () => void; let started = false; let payload: unknown; const pending = new Promise<void>(resolve => { finish = resolve; });
  server.use(http.post('/api/workitems/42/comments', async ({ request }) => { payload = await request.json(); started = true; await pending; return ok({ id: 55 }); }));
  const success = vi.spyOn(message, 'success'); mount(<WorkitemDetailPage />);
  const input = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...'); fireEvent.change(input, { target: { value: name } });
  fireEvent.click(screen.getByRole('button', { name: /发送/ })); await waitFor(() => expect(started).toBe(true)); await language('en-US'); finish();
  await waitFor(() => expect(success).toHaveBeenCalledWith('Comment sent.')); expect(payload).toMatchObject({ contentMd: name, targetAgentIds: [] });
});

it('refreshes a public byte-download failure already displayed by the preview without refetching on language change', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ status: 'SHARED', name: 'report.md', size: 1000 }))).mockResolvedValueOnce(new Response('', { status: 503 }));
  vi.stubGlobal('fetch', fetchMock);
  mount(<SharedArtifactPage />, `/api/share/workitems/awshare_${'a'.repeat(43)}/requests/41`, '/api/share/workitems/:token/:kind/:id');
  await screen.findByText('文件暂不可读，请稍后重试'); await language('en-US');
  expect(screen.getByText('The file cannot be read right now. Please try again later.')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it('updates collaboration enums and timestamps while preserving source names, custom relations and raw external errors', async () => {
  const { ExternalCollaborationCard } = await import('./components/ExternalCollaborationCard');
  mount(<ExternalCollaborationCard collaboration={{ provider: 'AONE', externalProjectId: 'project', externalWorkitemId: 'A42', externalUrl: 'https://example.test/42', sourceStatusId: 'custom', sourceStatusName: name, sourceLifecycle: 'CLOSED', reporter: { id: 1, provider: 'AONE', subjectType: 'USER', mappedUserId: null, subjectId: 'external7', displayName: name }, businessOwner: null, principalRelations: [], lastSyncAt: '2026-07-01T10:00:00Z', syncStatus: 'ACTION_REQUIRED', lastErrorCode: null, lastError: '外部原始错误' }} />);
  expect(screen.getByText('已关闭')).toBeInTheDocument(); await language('en-US');
  expect(screen.getByRole('link', { name: /Open Aone work item/ })).toHaveAttribute('href', 'https://example.test/42');
  expect(screen.getByText('Closed')).toBeInTheDocument(); expect(screen.getByText('Needs attention')).toBeInTheDocument();
  expect(screen.getByText(name)).toBeInTheDocument(); expect(screen.getByText(`${name}（external7）`)).toBeInTheDocument();
  expect(screen.getByText('外部原始错误')).toBeInTheDocument(); expect(screen.getByText('External work item sync needs attention')).toBeInTheDocument();
});

it('updates attachment accessibility and open delete confirmation without altering names or document IDs', async () => {
  const { RequirementDocumentsCard } = await import('./components/RequirementDocumentsCard');
  let payload: string | undefined;
  server.use(http.delete('/api/workitems/42/requirement-documents/77', ({ request }) => { payload = new URL(request.url).pathname; return ok(null); }));
  mount(<RequirementDocumentsCard workitemId={42} documents={[{ id: 77, workitemId: 42, dispatchId: null, name: 'requirements/需求 <Alice>.md', type: 'REQUIREMENT_DOC', size: 1000, gmtCreate: '2026-07-01T10:00:00Z' }]} />);
  fireEvent.click(screen.getByRole('button', { name: '删除 需求 <Alice>.md' })); await screen.findByText('确认删除 需求 <Alice>.md？');
  await language('en-US'); expect(screen.getByLabelText('Choose requirement/design context files')).toBeInTheDocument();
  expect(screen.getByText('Delete 需求 <Alice>.md?')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Preview 需求 <Alice>.md' })).toBeInTheDocument();
  await userEvent.click(within(screen.getByRole('tooltip')).getByRole('button', { name: 'Delete' }));
  await waitFor(() => expect(payload).toBe('/api/workitems/42/requirement-documents/77'));
});

it('updates cached mention choices and human/agent tabs while keeping the comment draft and authored roles', async () => {
  const { CommentInput } = await import('./components/CommentInput');
  const { SquadMembers } = await import('./components/SquadMembers');
  const agent = { ...human, userId: 8, displayId: 'A8', targetType: 'AGENT', role: 'AGENT', roleName: '数字员工', isAgent: true, name: '机器人 <Alice>', executorStatus: 'BUSY', status: 'ONLINE' };
  const custom = { ...agent, userId: 9, displayId: 'C9', targetType: 'CUSTOM', role: 'CUSTOM', roleName: '自定义角色 <em>role</em>', name: '自定义 <Alice>', executorStatus: 'OFFLINE' };
  server.use(http.get('/api/workitems/42/mention-candidates', () => ok([human, agent])));
  mount(<><CommentInput workitemId="42" participants={[human, agent]} /><SquadMembers participants={[human, agent, custom]} /></>);
  const input = screen.getByPlaceholderText('输入评论，键入 @ 选择成员...'); fireEvent.change(input, { target: { value: '@' } });
  await language('en-US');
  expect(within(screen.getByRole('menu')).getByText('Agent')).toBeInTheDocument(); expect(within(screen.getByRole('menu')).getByText('Person')).toBeInTheDocument();
  expect(screen.getByText('Running')).toBeInTheDocument(); expect(screen.getByText('ID: A8 · Squad member')).toBeInTheDocument();
  expect(screen.getByText('ID: C9 · 自定义角色 <em>role</em>')).toBeInTheDocument();
  expect(screen.getByText('自定义 <Alice>')).toBeInTheDocument(); expect(screen.getByText('机器人 <Alice>')).toBeInTheDocument();
  expect(input).toHaveValue('@'); expect(screen.getByPlaceholderText('Write a comment; type @ to choose a member…')).toBe(input);
  await userEvent.click(screen.getByRole('tab', { name: 'Human participants' })); expect(screen.getByLabelText('Human participant avatar')).toBeInTheDocument();
  expect(screen.getByText('ID: H7 · Human')).toBeInTheDocument();
  expect(screen.getByText(name)).toBeInTheDocument(); await language('zh-CN'); expect(screen.getByRole('tab', { name: /真人参与者/ })).toHaveAttribute('aria-selected', 'true');
  expect(screen.getByText('工号: H7 · 真人')).toBeInTheDocument();
  expect(input).toHaveValue('@'); expect(screen.getByPlaceholderText('输入评论，键入 @ 选择成员...')).toBe(input);
  expect(within(screen.getByRole('menu')).getByText('@机器人 <Alice>')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('tab', { name: /数字员工成员/ }));
  expect(screen.getByText('工号: A8 · 开发小队成员')).toBeInTheDocument();
  expect(screen.getByText('工号: C9 · 自定义角色 <em>role</em>')).toBeInTheDocument();
  expect(screen.getByText('自定义 <Alice>').querySelector('em')).toBeNull();
});

it('updates the header scheduled-origin rich sentence while preserving a literal HTML-like task name', async () => {
  const { WorkitemHeader } = await import('./components/WorkitemHeader');
  mount(<WorkitemHeader title={name} origin={{ type: 'SCHEDULED_TASK_RUN', id: 9, scheduledTaskName: name }} />);
  const link = screen.getByRole('link', { name: `来自 7×24 Task ${name} / Run #9` }); await language('en-US');
  expect(screen.getByRole('link', { name: `From 7×24 Task ${name} / Run #9` })).toBe(link); expect(link.querySelector('strong')).toBeNull();
});

it('ignores an old public byte-load failure after navigating to another share in the same route', async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>(resolve => { finish = resolve; });
  const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ status: 'SHARED', name: 'old.md', size: 1000 }))).mockImplementationOnce(() => pending).mockResolvedValueOnce(new Response(JSON.stringify({ status: 'PENDING', name: 'new.md', size: 1000 })));
  vi.stubGlobal('fetch', fetchMock); const path = `/api/share/workitems/awshare_${'a'.repeat(43)}/requests/`;
  const { router } = mount(<SharedArtifactPage />, path + '41', '/api/share/workitems/:token/:kind/:id');
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  await act(async () => { await router.navigate(path + '42'); }); await screen.findByText('文件正在生成');
  await act(async () => { finish(new Response('', { status: 503 })); });
  expect(screen.getByText('文件正在生成')).toBeInTheDocument(); expect(screen.queryByText('文件暂不可读，请稍后重试')).not.toBeInTheDocument();
});

it('retains an unknown public network diagnostic as literal text in both languages', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Provider 原文 <strong>network failure</strong>')));
  mount(<SharedArtifactPage />, `/api/share/workitems/awshare_${'a'.repeat(43)}/requests/41`, '/api/share/workitems/:token/:kind/:id');
  await screen.findByText('Provider 原文 <strong>network failure</strong>'); await language('en-US');
  expect(screen.getByText('Provider 原文 <strong>network failure</strong>')).toBeInTheDocument();
});

it('updates an already-ready public preview and download action without reloading authored Markdown', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ status: 'SHARED', name: '需求 <Alice>.md', size: 1000 }))).mockResolvedValueOnce(new Response('# 原始标题\n\nAI 原始正文'));
  vi.stubGlobal('fetch', fetchMock); const endpoint = `/api/share/workitems/awshare_${'a'.repeat(43)}/requests/41`;
  mount(<SharedArtifactPage />, endpoint, '/api/share/workitems/:token/:kind/:id'); await screen.findByRole('heading', { name: '原始标题' });
  await language('en-US'); expect(screen.getByText('Ready')).toBeInTheDocument(); expect(screen.getByRole('link', { name: /Download original file/ })).toHaveAttribute('href', endpoint + '?download=true');
  expect(screen.getByRole('heading', { name: '需求 <Alice>.md' })).toBeInTheDocument(); expect(screen.getByText('AI 原始正文')).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(2);
});


it('switches attachment controls without altering a staged filename or uploading it', async () => {
  const { CommentInput } = await import('./components/CommentInput');
  mount(<CommentInput workitemId="42" />);
  fireEvent.change(screen.getByTestId('comment-file-input'), { target: { files: [new File(['notes'], '原始 [name].txt', { type: 'text/plain' })] } });
  expect(screen.getByRole('button', { name: '移除附件 原始 [name].txt' })).toBeInTheDocument();
  await language('en-US');
  expect(screen.getByRole('button', { name: /Add files or images/ })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Remove attachment 原始 [name].txt' })).toBeInTheDocument();
  expect(screen.getByText('原始 [name].txt')).toBeInTheDocument();
  expect(screen.getByText(/Any file type; drop files or paste screenshots/)).toBeInTheDocument();
});

it('uploads a comment attachment before submitting and resolves the saved timeline reference', async () => {
  const fileName = 'comment.txt';
  const document = { id: 99, workitemId: 42, dispatchId: null, name: `requirements/${fileName}`, type: 'REQUIREMENT_DOC', size: 5, gmtCreate: '2026-10-14T00:00:00Z' };
  let uploaded = false; let comment = ''; let multipart = false;
  server.use(
    http.post('/api/workitems/42/requirement-documents', async ({ request }) => {
      multipart = request.headers.get('content-type')?.startsWith('multipart/form-data; boundary=') ?? false;
      const body = await request.text();
      expect(body).toContain('name="files"');
      uploaded = true; return ok([document]);
    }),
    http.get('/api/workitems/42/requirement-documents', () => ok(uploaded ? [document] : [])),
    http.post('/api/workitems/42/comments', async ({ request }) => {
      expect(uploaded).toBe(true); comment = (await request.json() as { contentMd: string }).contentMd; return ok({ id: 100 });
    }),
    http.get('/api/workitems/42/unified-timeline', () => ok(comment ? [{ id: 100, type: 'comment', authorId: 7, authorType: 'HUMAN', isAgent: false, content: comment, gmtCreate: '2026-10-14T00:00:00Z' }] : [])),
    http.get('/api/artifacts/99/download', () => ok('https://example.test/comment.txt')),
  );
  mount(<WorkitemDetailPage />); await screen.findByRole('heading', { name });
  fireEvent.change(screen.getByTestId('comment-file-input'), { target: { files: [new File(['notes'], fileName, { type: 'text/plain' })] } });
  fireEvent.click(screen.getByRole('button', { name: /发送/ }));
  expect(screen.getByRole('button', { name: `移除附件 ${fileName}` })).toBeInTheDocument();
  await waitFor(() => expect(uploaded).toBe(true), { timeout: 2000 });
  await waitFor(() => expect(comment).toBe('` requirements/comment.txt `'), { timeout: 5000 });
  const link = await screen.findByTestId('artifact-inline-link');
  expect(multipart).toBe(true); expect(comment).toBe('` requirements/comment.txt `');
  expect(link).toHaveTextContent('requirements/comment.txt');
  expect(screen.queryByRole('button', { name: `移除附件 ${fileName}` })).not.toBeInTheDocument();
  const fetchMock = vi.fn().mockResolvedValue(new Response('notes', { headers: { 'Content-Type': 'text/plain' } }));
  vi.stubGlobal('fetch', fetchMock); fireEvent.click(link);
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/artifacts/99/preview', expect.any(Object)));
});


it('rejects file selection, paste and drop for read-only users before upload', async () => {
  const { CommentInput } = await import('./components/CommentInput');
  useAuthStore.setState({ accessLevel: 'READ_ONLY' });
  const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  mount(<CommentInput workitemId="42" />);
  const file = new File(['notes'], 'readonly.txt', { type: 'text/plain' });
  fireEvent.change(screen.getByTestId('comment-file-input'), { target: { files: [file] } });
  fireEvent.paste(screen.getByPlaceholderText('输入评论，键入 @ 选择成员...'), { clipboardData: { files: [file] } });
  fireEvent.drop(screen.getByTestId('comment-attachment-dropzone'), { dataTransfer: { types: ['Files'], files: [file] } });
  expect(error).toHaveBeenCalledTimes(3);
  expect(screen.queryByRole('button', { name: '移除附件 readonly.txt' })).not.toBeInTheDocument();
});
