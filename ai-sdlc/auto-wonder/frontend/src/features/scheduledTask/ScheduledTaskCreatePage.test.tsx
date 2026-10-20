import { afterEach as resetAfterEach } from 'vitest';
import { act } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { createAppRoutes } from '@/app/router';
import { i18n } from '@/shared/i18n';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { backendErrorMessage, initialStatusForCreate, ScheduledTaskCreatePage } from './ScheduledTaskCreatePage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/scheduled-tasks/new']}>
        <Routes>
          <Route path="/scheduled-tasks/new" element={<ScheduledTaskCreatePage />} />
          <Route path="/scheduled-tasks/:id" element={<div>任务详情</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// antd's static message API renders outside the component tree, so the error branches are
// asserted through spies on its arguments and every spy is restored before the next test.
afterEach(() => { vi.restoreAllMocks(); });

const SQUAD_HANDLERS = [
  http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 1, name: '研发小队', description: '', memberCount: 1, gmtCreate: '' }] })),
  http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ agentId: 11, agentName: '回归工程师', roleCode: 'TEST', sdlcId: null }] })),
];

const createHandler = () => http.post('/api/scheduled-tasks', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 9, name: '主干夜间回归', status: 'PAUSED', version: 3 } }));
const uploadHandler = (body: Record<string, unknown>) => http.post('/api/scheduled-tasks/9/documents', () => HttpResponse.json(body));
const enableHandler = (body: Record<string, unknown>) => http.post('/api/scheduled-tasks/9/enable', () => HttpResponse.json(body));
const ok = { success: true, code: '0', message: '', data: [] };

async function fillForm(user: ReturnType<typeof userEvent.setup>, container: HTMLElement, documentName?: string) {
  await user.type(screen.getByLabelText('任务名称'), '主干夜间回归');
  await user.type(screen.getByLabelText('任务指令'), '每天对主干进行回归并汇总结果。');
  await user.click(screen.getByLabelText('小队'));
  await user.click(await screen.findByText('研发小队'));
  await user.click(screen.getByLabelText('首个数字员工'));
  await user.click(await screen.findByText('回归工程师'));
  await user.click(screen.getByText('每天'));
  if (!documentName) return;
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  // fireEvent reaches the input regardless of any accept filtering, like an OS file dialog handing over an unsupported type.
  fireEvent.change(input, { target: { files: [new File(['dummy'], documentName, { type: 'application/octet-stream' })] } });
  await screen.findByText(documentName);
}


function documentInput(container: HTMLElement): HTMLInputElement {
  return container.querySelector('input[type="file"]') as HTMLInputElement;
}

// Mirrors the DataTransfer shape the browser hands to drop and paste handlers.
function fileTransfer(files: File[]) {
  return { types: ['Files'], files, items: files.map((file) => ({ kind: 'file', getAsFile: () => file })) };
}

function screenshot(name = 'image.png') {
  return new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' });
}

describe('ScheduledTaskCreatePage', () => {
  it('keeps a document-bearing task paused until documents are attached', () => {
    expect(initialStatusForCreate('ACTIVE', 1)).toBe('PAUSED');
    expect(initialStatusForCreate('PAUSED', 1)).toBe('PAUSED');
  });
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', message: '', data: ['2026-08-12T18:00:00Z'] })));
  });

  it('submits canonical cron and selected squad agent', async () => {
    let lastBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 1, name: '研发小队', description: '', memberCount: 1, gmtCreate: '' }] })),
      http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ agentId: 11, agentName: '回归工程师', roleCode: 'TEST', sdlcId: null }] })),
      http.post('/api/scheduled-tasks', async ({ request }) => {
        lastBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ success: true, code: '0', message: '', data: { id: 9, ...lastBody, status: 'ACTIVE', version: 0 } });
      }),
    );

    renderPage();
    expect(screen.getByRole('button', { name: '返回定时任务列表' })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('任务名称'), '主干夜间回归');
    await user.type(screen.getByLabelText('任务指令'), '每天对主干进行回归并汇总结果。');
    await user.click(screen.getByLabelText('小队'));
    await user.click(await screen.findByText('研发小队'));
    await user.click(screen.getByLabelText('首个数字员工'));
    await user.click(await screen.findByText('回归工程师'));
    await user.click(screen.getByText('每天'));
    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(lastBody).toMatchObject({
      name: '主干夜间回归', squadId: 1, initialAgentId: 11,
      scheduleType: 'CRON', cronExpression: '0 0 2 * * *',
      timezone: 'Asia/Shanghai', sessionMode: 'ISOLATED', overlapPolicy: 'SKIP',
    }));
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('renders the server-authoritative preview and selected Agent SDLC context', async () => {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 1, name: '研发小队', description: '', memberCount: 1, gmtCreate: '' }] })),
      http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ agentId: 11, agentName: '回归工程师', roleCode: 'TEST', sdlcName: '回归流程', sdlcSteps: [{ id: 100, stepOrder: 1, name: '执行回归', handlerType: 'AGENT', handlerRoleRef: null }] }] })),
      http.get('/api/scheduled-tasks/preview', ({ request }) => {
        const params = new URL(request.url).searchParams;
        expect(params.get('cronExpression')).toBe('0 0 2 * * *');
        expect(params.get('timezone')).toBe('Asia/Shanghai');
        expect(params.get('count')).toBe('5');
        return HttpResponse.json({ success: true, code: '0', message: '', data: ['2026-08-12T18:00:00Z', '2026-08-13T18:00:00Z'] });
      }),
    );
    renderPage();
    // ScheduleEditor 把服务端下发的未来执行时间按本地时区展示（zh-CN 格式化），不再直接渲染 ISO 串
    expect(await screen.findByText(new Date('2026-08-12T18:00:00Z').toLocaleString('zh-CN'))).toBeInTheDocument();
    expect(screen.getByText(new Date('2026-08-13T18:00:00Z').toLocaleString('zh-CN'))).toBeInTheDocument();
  });

  it('surfaces the backend reason when document upload fails', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    server.use(
      ...SQUAD_HANDLERS,
      createHandler(),
      uploadHandler({ success: false, code: 'PARAM_INVALID', message: '仅支持 .md/.txt/.html/.pdf/.png/.jpg/.webp 格式', traceId: 'trace-upload-1' }),
    );
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container, 'spec.docx');

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith(
      '任务已保存为暂停，需求文档上传失败：仅支持 .md/.txt/.html/.pdf/.png/.jpg/.webp 格式；请在任务详情重试上传后再启用。',
    ));
    // code and traceId stay out of the user-facing copy.
    expect(String(errorSpy.mock.calls[0][0])).not.toContain('trace-upload-1');
    expect(String(errorSpy.mock.calls[0][0])).not.toContain('PARAM_INVALID');
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('falls back to the generic copy when the backend message is blank', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    server.use(
      ...SQUAD_HANDLERS,
      createHandler(),
      uploadHandler({ success: false, code: 'STORAGE_ERROR', message: '   ', traceId: null }),
    );
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container, 'spec.md');

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith(
      '任务已保存为暂停，需求文档上传失败；请在任务详情重试上传后再启用。',
    ));
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('surfaces the backend reason when enabling an uploaded task fails', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    server.use(
      ...SQUAD_HANDLERS,
      createHandler(),
      uploadHandler(ok),
      enableHandler({ success: false, code: 'CONFLICT', message: '版本号已过期，请刷新后重试', traceId: null }),
    );
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container, 'spec.md');

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith(
      '需求文档已上传，但启用失败：版本号已过期，请刷新后重试；任务仍为暂停状态，请在任务详情重试启用。',
    ));
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('falls back to the generic enable copy when the backend message is blank', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    server.use(
      ...SQUAD_HANDLERS,
      createHandler(),
      uploadHandler(ok),
      enableHandler({ success: false, code: 'CONFLICT', message: ' ', traceId: null }),
    );
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container, 'spec.md');

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith(
      '需求文档已上传，但启用失败；任务仍为暂停状态，请在任务详情重试启用。',
    ));
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('still reports success when upload and enable both succeed', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const successSpy = vi.spyOn(message, 'success');
    server.use(...SQUAD_HANDLERS, createHandler(), uploadHandler(ok), enableHandler(ok));
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container, 'spec.md');

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('定时任务已创建并启用'));
    expect(errorSpy).not.toHaveBeenCalled();
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('skips document upload and enable when no document is attached', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    let uploadCalled = false;
    let enableCalled = false;
    server.use(
      ...SQUAD_HANDLERS,
      createHandler(),
      http.post('/api/scheduled-tasks/9/documents', () => { uploadCalled = true; return HttpResponse.json(ok); }),
      http.post('/api/scheduled-tasks/9/enable', () => { enableCalled = true; return HttpResponse.json(ok); }),
    );
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container);

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(screen.getByText('任务详情')).toBeInTheDocument());
    expect(uploadCalled).toBe(false);
    expect(enableCalled).toBe(false);
    expect(errorSpy).not.toHaveBeenCalled();
  });
});

describe('ScheduledTaskCreatePage document upload', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', message: '', data: ['2026-08-12T18:00:00Z'] })));
  });

  it('allows all file types and advertises paste', () => {
    const { container } = renderPage();
    expect(documentInput(container).accept).toBe('');
    expect(screen.getByText(/Ctrl\/Cmd\+V 粘贴截图/)).toBeInTheDocument();
  });

  it('accepts Excel through change', async () => {
    const { container } = renderPage();
    const file = new File(['sheet'], 'notes.xlsx', { type: 'application/vnd.ms-excel' });
    fireEvent.change(documentInput(container), { target: { files: [file] } });
    expect(await screen.findByText('notes.xlsx')).toBeInTheDocument();
  });

  it('rejects a file above the 5 MB single-file limit', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const { container } = renderPage();

    fireEvent.change(documentInput(container), { target: { files: [new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'big.pdf', { type: 'application/pdf' })] } });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('单个附件大小不能超过 5 MB'));
    expect(screen.queryByText('big.pdf')).not.toBeInTheDocument();
  });

  it('rejects a multi-file selection whose total exceeds 20 MB as one batch', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const { container } = renderPage();
    const heavy = Array.from({ length: 6 }, (_, index) => new File([new Uint8Array(4 * 1024 * 1024)], `heavy-${index}.pdf`, { type: 'application/pdf' }));

    fireEvent.change(documentInput(container), { target: { files: heavy } });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('需求/设计上下文总大小不能超过 20 MB'));
    expect(errorSpy).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('heavy-0.pdf')).not.toBeInTheDocument();
  });

  it('stops at ten attachments and keeps the ones already accepted', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const { container } = renderPage();
    const accepted = Array.from({ length: 10 }, (_, index) => new File(['x'], `doc-${index}.md`, { type: 'text/markdown' }));

    fireEvent.change(documentInput(container), { target: { files: accepted } });
    await screen.findByText('doc-9.md');

    fireEvent.change(documentInput(container), { target: { files: [new File(['x'], 'doc-10.md', { type: 'text/markdown' })] } });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('最多上传 10 个需求/设计上下文附件'));
    expect(screen.queryByText('doc-10.md')).not.toBeInTheDocument();
    expect(screen.getByText('doc-0.md')).toBeInTheDocument();
    expect(screen.getByText('doc-9.md')).toBeInTheDocument();
  });

  it('rejects a selection that repeats a name already attached', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const { container } = renderPage();

    fireEvent.change(documentInput(container), { target: { files: [new File(['a'], 'spec.md', { type: 'text/markdown' })] } });
    await screen.findByText('spec.md');
    fireEvent.change(documentInput(container), { target: { files: [new File(['b'], 'spec.md', { type: 'text/markdown' })] } });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('附件已存在：spec.md'));
    expect(screen.getAllByText('spec.md')).toHaveLength(1);
  });

  it('rejects a batch containing the same name twice', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const { container } = renderPage();

    fireEvent.change(documentInput(container), {
      target: { files: [new File(['a'], 'twice.md', { type: 'text/markdown' }), new File(['b'], 'twice.md', { type: 'text/markdown' })] },
    });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('附件已存在：twice.md'));
    expect(screen.queryByText('twice.md')).not.toBeInTheDocument();
  });

  it('explains folder and non-file drops instead of failing silently', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    renderPage();
    const dropzone = screen.getByTestId('scheduled-task-document-dropzone');

    fireEvent.drop(dropzone, { dataTransfer: { types: ['Files'], files: [], items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }] } });
    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('不支持上传文件夹，请拖入文件'));

    fireEvent.drop(dropzone, { dataTransfer: { types: ['text/uri-list'], files: [], items: [{ kind: 'string', getAsFile: () => null }] } });
    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('未识别到可上传的文件，请拖入本地文件'));
  });

  it('accepts a document dropped on the dragger', async () => {
    renderPage();

    // rc-upload binds its drop handler to the inner button span, which is what sits under the pointer.
    fireEvent.drop(screen.getByText(/点击或拖拽上传需求文档/), { dataTransfer: fileTransfer([new File(['x'], 'plan.md', { type: 'text/markdown' })]) });

    expect(await screen.findByText('plan.md')).toBeInTheDocument();
  });

  it('accepts Excel through drop', async () => {
    renderPage();
    const file = new File(['sheet'], 'notes.xlsx', { type: 'application/vnd.ms-excel' });
    fireEvent.drop(screen.getByText(/点击或拖拽上传需求文档/), { dataTransfer: fileTransfer([file]) });
    expect(await screen.findByText('notes.xlsx')).toBeInTheDocument();
  });

  it('applies the shared attachment limit to a dropped batch', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    renderPage();
    const eleven = Array.from({ length: 11 }, (_, index) => new File(['x'], `drop-${index}.md`, { type: 'text/markdown' }));

    fireEvent.drop(screen.getByText(/点击或拖拽上传需求文档/), { dataTransfer: fileTransfer(eleven) });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('最多上传 10 个需求/设计上下文附件'));
    expect(screen.queryByText('drop-0.md')).not.toBeInTheDocument();
  });

  it('prevents the browser from opening a document dropped outside the uploader', () => {
    renderPage();

    expect(fireEvent.drop(document.body, { dataTransfer: fileTransfer([new File(['x'], 'plan.md', { type: 'text/markdown' })]) })).toBe(false);
  });

  it('accepts a pasted screenshot with a unique legal name and uploads it on submit', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const successSpy = vi.spyOn(message, 'success');
    // msw serializes this environment's File objects without their names, so the payload is asserted
    // at the FormData boundary, where the multipart field is built.
    const appendSpy = vi.spyOn(FormData.prototype, 'append');
    let uploadContentType = '';
    server.use(
      ...SQUAD_HANDLERS,
      createHandler(),
      enableHandler(ok),
      http.post('/api/scheduled-tasks/9/documents', async ({ request }) => {
        uploadContentType = request.headers.get('content-type') ?? '';
        await request.text();
        return HttpResponse.json(ok);
      }),
    );
    const user = userEvent.setup();
    const { container } = renderPage();
    await fillForm(user, container);

    const dropzone = screen.getByTestId('scheduled-task-document-dropzone');
    expect(fireEvent.paste(dropzone, { clipboardData: fileTransfer([screenshot()]) })).toBe(false);
    const pastedItem = await screen.findByText(/^粘贴-\d{8}-\d{6}-\d+\.png$/);

    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('定时任务已创建并启用'));
    expect(errorSpy).not.toHaveBeenCalled();
    expect(uploadContentType).toContain('multipart/form-data');
    const uploaded = appendSpy.mock.calls.filter(([field]) => field === 'files').map(([, value]) => value as File);
    expect(uploaded).toHaveLength(1);
    expect(uploaded[0].name).toBe(pastedItem.textContent);
    expect(uploaded[0].type).toBe('image/png');
  });

  it('gives every pasted screenshot a distinct name, including within one paste', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    renderPage();
    const dropzone = screen.getByTestId('scheduled-task-document-dropzone');

    fireEvent.paste(dropzone, { clipboardData: fileTransfer([screenshot(), screenshot()]) });
    const firstBatch = await screen.findAllByText(/^粘贴-\d{8}-\d{6}-\d+\.png$/);
    expect(firstBatch).toHaveLength(2);

    fireEvent.paste(dropzone, { clipboardData: fileTransfer([screenshot()]) });
    const all = await screen.findAllByText(/^粘贴-\d{8}-\d{6}-\d+\.png$/);
    expect(all).toHaveLength(3);
    expect(new Set(all.map((node) => node.textContent)).size).toBe(3);
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('accepts an arbitrary named clipboard file without renaming it', async () => {
    renderPage();
    fireEvent.paste(screen.getByTestId('scheduled-task-document-dropzone'), { clipboardData: fileTransfer([new File(['bytes'], 'report.custom')]) });
    expect(await screen.findByText('report.custom')).toBeInTheDocument();
  });

  it('leaves plain-text pastes untouched', () => {
    const errorSpy = vi.spyOn(message, 'error');
    renderPage();
    const textOnly = { types: ['text/plain'], files: [], items: [{ kind: 'string', getAsFile: () => null }] };

    expect(fireEvent.paste(screen.getByLabelText('任务指令'), { clipboardData: textOnly })).toBe(true);
    expect(fireEvent.paste(screen.getByTestId('scheduled-task-document-dropzone'), { clipboardData: textOnly })).toBe(true);
    expect(errorSpy).not.toHaveBeenCalled();
  });
});

describe('backendErrorMessage', () => {
  it('reads the message of the ApiError produced by the shared interceptor', () => {
    expect(backendErrorMessage(new ApiError('PARAM_INVALID', '单文件不能超过 5MB', 'trace-1'))).toBe('单文件不能超过 5MB');
  });

  it('reads the message of a plain Error and trims surrounding whitespace', () => {
    expect(backendErrorMessage(new Error('  网络异常  '))).toBe('网络异常');
  });

  it('returns an empty string for a blank message so callers can fall back', () => {
    expect(backendErrorMessage(new Error(''))).toBe('');
    expect(backendErrorMessage(new Error('   '))).toBe('');
  });

  it('returns an empty string for a non-Error throw instead of throwing itself', () => {
    expect(backendErrorMessage('boom')).toBe('');
    expect(backendErrorMessage(undefined)).toBe('');
    expect(backendErrorMessage(null)).toBe('');
    expect(backendErrorMessage({ message: 'boom' })).toBe('');
  });
});

function renderRoutedCreate() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const route = createAppRoutes().flatMap(route => route.children ?? []).find(route => route.path === '/scheduled-tasks/new')!;
  const router = createMemoryRouter([route, { path: '/scheduled-tasks/:id', element: <div>任务详情</div> }], { initialEntries: ['/scheduled-tasks/new'] });
  return { router, ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>) };
}

it('updates cached create route errors while preserving drafts, files and selected Agent', async () => {
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  server.use(...SQUAD_HANDLERS, http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', data: ['2026-08-12T18:00:00Z'] })));
  const user = userEvent.setup();
  const { container } = renderRoutedCreate();
  await screen.findByLabelText('任务名称');
  await user.type(screen.getByLabelText('任务名称'), '<b>自定义任务</b>');
  await user.click(screen.getByLabelText('小队'));
  await user.click(await screen.findByText('研发小队'));
  await user.click(screen.getByLabelText('首个数字员工'));
  await user.click(await screen.findByText('回归工程师'));
  fireEvent.change(documentInput(container), { target: { files: [new File(['x'], '原文.md', { type: 'text/markdown' })] } });
  await screen.findByText('原文.md');
  await user.click(screen.getByRole('button', { name: '创建并启用' }));
  await screen.findByText('请描述希望数字员工周期完成的工作');
  const originalInput = screen.getByLabelText('任务名称');
  await act(() => i18n.changeLanguage('en-US'));
  expect(await screen.findByText('Describe the work the Agent should complete periodically')).toBeInTheDocument();
  expect(screen.getByLabelText('Task name')).toBe(originalInput);
  expect(originalInput).toHaveValue('<b>自定义任务</b>');
  expect(screen.getByLabelText('Task instruction')).toHaveValue('');
  expect(screen.getByText(new Date('2026-08-12T18:00:00Z').toLocaleString('en-US'))).toBeInTheDocument();
  expect(screen.getByText('原文.md')).toBeInTheDocument();
  expect(screen.getAllByText('回归工程师').length).toBeGreaterThan(0);
  expect(screen.queryByText('Select a squad')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Requirement document upload area (click, drag or paste)')).toBeInTheDocument();
  await user.click(screen.getByText('Run policy and continuous session settings'));
  expect(screen.getByText('Isolated session (recommended)')).toBeInTheDocument();
  await act(() => i18n.changeLanguage('zh-CN'));
});

it('does not validate pristine fields when switching the cached create route', async () => {
  server.use(...SQUAD_HANDLERS, http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', data: [] })));
  renderRoutedCreate();
  await screen.findByLabelText('任务名称');
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByLabelText('Task name')).toHaveValue('');
  expect(document.querySelector('.ant-form-item-explain-error')).toBeNull();
  await act(() => i18n.changeLanguage('zh-CN'));
});

it('uses the completion language for deferred document failure and preserves backend detail and upload order', async () => {
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  let release!: () => void;
  let uploaded = false;
  let created: unknown;
  const deferred = new Promise<void>(resolve => { release = resolve; });
  const errors = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  const append = vi.spyOn(FormData.prototype, 'append');
  server.use(...SQUAD_HANDLERS,
    http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', data: [] })),
    http.post('/api/scheduled-tasks', async ({ request }) => { created = await request.json(); return HttpResponse.json({ success: true, code: '0', data: { id: 9, version: 3 } }); }),
    http.post('/api/scheduled-tasks/9/documents', async () => { uploaded = true; await deferred; return HttpResponse.json({ success: false, code: 'STORAGE_ERROR', message: '原始后端原因' }); }),
  );
  const user = userEvent.setup();
  const { container } = renderPage();
  await fillForm(user, container, '原文.md');
  await user.click(screen.getByRole('button', { name: '创建并启用' }));
  await waitFor(() => expect(uploaded).toBe(true));
  await act(() => i18n.changeLanguage('en-US'));
  act(() => release());
  await waitFor(() => expect(errors).toHaveBeenCalledWith('Task saved as paused; requirement document upload failed: 原始后端原因. Retry the upload in task details before enabling it.'));
  expect(created).toMatchObject({ initialStatus: 'PAUSED', timezone: 'Asia/Shanghai', cronExpression: '0 0 2 * * *', squadId: 1, initialAgentId: 11 });
  expect(append.mock.calls.find(([key]) => key === 'files')?.[1]).toMatchObject({ name: '原文.md' });
  await act(() => i18n.changeLanguage('zh-CN'));
});

resetAfterEach(async () => { await act(() => i18n.changeLanguage('zh-CN')); });

it('keeps one-time date and time values across a route locale switch and submits the same ISO instant', async () => {
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  let body: unknown;
  server.use(...SQUAD_HANDLERS,
    http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', data: [] })),
    http.post('/api/scheduled-tasks', async ({ request }) => { body = await request.json(); return HttpResponse.json({ success: true, code: '0', data: { id: 9, version: 0 } }); }),
  );
  const user = userEvent.setup();
  const { container } = renderRoutedCreate();
  await screen.findByLabelText('任务名称');
  await fillForm(user, container);
  await user.click(screen.getByText('单次执行'));
  const dateInput = screen.getByLabelText('执行日期');
  const timeInput = screen.getByLabelText('执行时间');
  fireEvent.change(dateInput, { target: { value: '2099-10-03' } });
  fireEvent.keyDown(dateInput, { key: 'Enter', code: 'Enter' });
  fireEvent.change(timeInput, { target: { value: '08:30' } });
  fireEvent.keyDown(timeInput, { key: 'Enter', code: 'Enter' });
  await user.click(screen.getByLabelText('任务名称'));
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByLabelText('Execution date')).toBe(dateInput);
  expect(screen.getByLabelText('Execution time')).toBe(timeInput);
  expect(dateInput).toHaveValue('2099-10-03');
  expect(timeInput).toHaveValue('08:30');
  await user.click(screen.getByRole('button', { name: 'Create and enable' }));
  await waitFor(() => expect(body).toMatchObject({ scheduleType: 'ONCE', runAt: new Date(2099, 9, 3, 8, 30, 0, 0).toISOString(), timezone: 'Asia/Shanghai', initialStatus: 'ACTIVE', squadId: 1, initialAgentId: 11 }));
  expect(body).not.toHaveProperty('cronExpression');
});

it.each([
  [false, '原始启用错误', 'Requirement documents uploaded, but enabling failed: 原始启用错误. The task is still paused; retry enabling it in task details.'],
  [true, '', 'Scheduled task created and enabled'],
])('uses the completion language after a deferred enable (%s)', async (success, reason, expected) => {
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  let release!: () => void;
  let enabling = false;
  let version: string | null = null;
  const pending = new Promise<void>(resolve => { release = resolve; });
  const notification = vi.spyOn(message, success ? 'success' : 'error').mockImplementation(() => undefined as never);
  server.use(...SQUAD_HANDLERS, createHandler(), uploadHandler(ok),
    http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', data: [] })),
    http.post('/api/scheduled-tasks/9/enable', async ({ request }) => { version = new URL(request.url).searchParams.get('version'); enabling = true; await pending; return HttpResponse.json({ success, code: success ? '0' : 'CONFLICT', message: reason, data: null }); }),
  );
  const user = userEvent.setup();
  const { container } = renderRoutedCreate();
  await screen.findByLabelText('任务名称');
  await fillForm(user, container, '原始.md');
  await user.click(screen.getByRole('button', { name: '创建并启用' }));
  await waitFor(() => expect(enabling).toBe(true));
  await act(() => i18n.changeLanguage('en-US'));
  act(() => release());
  await waitFor(() => expect(notification).toHaveBeenCalledWith(expected));
  expect(version).toBe('3');
  expect(await screen.findByText('任务详情')).toBeInTheDocument();
});
