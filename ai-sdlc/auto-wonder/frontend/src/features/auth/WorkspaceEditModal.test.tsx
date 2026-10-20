import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Form, message } from 'antd';
import { i18n } from '@/shared/i18n';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import type { WorkspaceInfo } from '@/shared/types/common';
import { WorkspaceEditModal } from './WorkspaceEditModal';

// antd inserts a thin space between exactly two CJK characters in a Button label (`保存` renders
// as `保 存`), so every button lookup goes through a whitespace-tolerant matcher.
function buttonName(label: string) {
  return new RegExp(label.split('').join('\\s*'));
}

const WORKSPACE: WorkspaceInfo = {
  id: 31,
  name: '星云工坊',
  description: '多 Agent 研发协作空间',
  background: '云原生研发',
  version: 7,
};

const NAME_PLACEHOLDER = '输入工作空间名称';
const DESCRIPTION_PLACEHOLDER = '简要描述工作空间用途';
const BACKGROUND_PLACEHOLDER = '工作空间的行业背景、技术栈、团队规模等信息';

function renderModal(initial: WorkspaceInfo | null) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const onClose = vi.fn();

  function Harness({ workspace }: { workspace: WorkspaceInfo | null }) {
    return (
      <QueryClientProvider client={queryClient}>
        <WorkspaceEditModal workspace={workspace} onClose={onClose} />
      </QueryClientProvider>
    );
  }

  const view = render(<Harness workspace={initial} />);
  return {
    ...view,
    onClose,
    queryClient,
    setWorkspace: (workspace: WorkspaceInfo | null) => view.rerender(<Harness workspace={workspace} />),
  };
}

/** Captures every PUT body (tagged with the path id) and answers with a caller-supplied response. */
function mockUpdate(responder: (body: Record<string, unknown>) => Response) {
  const bodies: Array<Record<string, unknown>> = [];
  server.use(
    http.put('/api/workspaces/:id', async ({ params, request }) => {
      const body = await request.json() as Record<string, unknown>;
      bodies.push({ ...body, __id: Number(params.id) });
      return responder(body);
    }),
  );
  return bodies;
}

function ok(body: Record<string, unknown>) {
  return HttpResponse.json({
    success: true, code: '0', message: '', traceId: null, data: { id: 31, ...body },
  });
}

function fail(code: string, text: string, status?: number) {
  return HttpResponse.json(
    { success: false, code, message: text, data: null, traceId: `trace-${code}` },
    status ? { status } : undefined,
  );
}

function spySuccess() {
  return vi.spyOn(message, 'success').mockImplementation(
    () => undefined as unknown as ReturnType<typeof message.success>,
  );
}

function spyError() {
  return vi.spyOn(message, 'error').mockImplementation(
    () => undefined as unknown as ReturnType<typeof message.error>,
  );
}

describe('WorkspaceEditModal', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('stays closed while no workspace is selected', () => {
    renderModal(null);

    expect(screen.queryByPlaceholderText(NAME_PLACEHOLDER)).not.toBeInTheDocument();
    expect(screen.queryByText(/编辑「/)).not.toBeInTheDocument();
  });

  it('does not read the disconnected real Form while the workspace editor is closed', async () => {
    const read = vi.fn();
    const original = Form.useForm;
    const seen = new Set();
    vi.spyOn(Form, 'useForm').mockImplementation(initial => {
      const result = original(initial);
      const instance = result[0];
      if (!seen.has(instance)) {
        seen.add(instance);
        const getErrors = instance.getFieldsError.bind(instance);
        vi.spyOn(instance, 'getFieldsError').mockImplementation(names => { read(); return getErrors(names); });
      }
      return result;
    });
    renderModal(null);
    expect(read).not.toHaveBeenCalled();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(read).not.toHaveBeenCalled();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
  });

  it('echoes the stored name, description and background', async () => {
    renderModal(WORKSPACE);

    expect(await screen.findByText('编辑「星云工坊」')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(NAME_PLACEHOLDER)).toHaveValue('星云工坊');
    expect(screen.getByPlaceholderText(DESCRIPTION_PLACEHOLDER)).toHaveValue('多 Agent 研发协作空间');
    // The background is never rendered on the card, so the dialog is the only place it can be
    // read back and corrected (F1.3/F1.4).
    expect(screen.getByPlaceholderText(BACKGROUND_PLACEHOLDER)).toHaveValue('云原生研发');
  });

  it('echoes empty strings rather than "null" for a workspace without description or background', async () => {
    renderModal({ id: 32, name: '空白空间', description: '', background: null, version: null });

    expect(await screen.findByPlaceholderText(DESCRIPTION_PLACEHOLDER)).toHaveValue('');
    expect(screen.getByPlaceholderText(BACKGROUND_PLACEHOLDER)).toHaveValue('');
  });

  it('keeps an in-progress edit when a refetch hands back a new object for the same row', async () => {
    const user = userEvent.setup();
    const { setWorkspace } = renderModal(WORKSPACE);

    const nameInput = await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.clear(nameInput);
    await user.type(nameInput, '改名中');
    expect(nameInput).toHaveValue('改名中');

    // F1.3: the echo effect is keyed on workspace.id, not on object identity. A background list
    // refetch returns a fresh object for the same row; keying on the object would wipe the
    // half-finished edit on every poll.
    setWorkspace({ ...WORKSPACE, version: 8 });
    expect(nameInput).toHaveValue('改名中');

    // Opening a genuinely different row does re-echo, so the effect is not simply inert.
    setWorkspace({ id: 32, name: '另一个空间', description: '另一个描述', background: null, version: 1 });
    await waitFor(() => expect(nameInput).toHaveValue('另一个空间'));
    expect(screen.getByPlaceholderText(DESCRIPTION_PLACEHOLDER)).toHaveValue('另一个描述');
    expect(screen.getByText('编辑「另一个空间」')).toBeInTheDocument();
  });

  it('sends the stored version so the server can enforce the optimistic lock', async () => {
    const user = userEvent.setup();
    const success = spySuccess();
    const bodies = mockUpdate(() => ok({ name: '星云工坊 2' }));
    const { onClose } = renderModal(WORKSPACE);

    const nameInput = await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.clear(nameInput);
    await user.type(nameInput, '星云工坊 2');
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    // F1.5: `version` is the org.version the row was loaded with. Without it the server cannot
    // tell a stale save from a fresh one and would silently overwrite a concurrent edit.
    expect(bodies[0]).toEqual({
      __id: 31,
      name: '星云工坊 2',
      description: '多 Agent 研发协作空间',
      background: '云原生研发',
      version: 7,
    });
    await waitFor(() => expect(success).toHaveBeenCalledWith('工作空间已更新'));
    expect(onClose).toHaveBeenCalledTimes(1);
    success.mockRestore();
  });

  it('falls back to version 0 when the row carries no version', async () => {
    const user = userEvent.setup();
    spySuccess();
    const bodies = mockUpdate(() => ok({}));
    renderModal({ id: 33, name: '无版本号空间', description: '', background: null });

    await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ __id: 33, version: 0 });
  });

  it('submits a cleared description and background instead of keeping the echoed value', async () => {
    const user = userEvent.setup();
    spySuccess();
    const bodies = mockUpdate(() => ok({}));
    renderModal(WORKSPACE);

    await user.clear(await screen.findByPlaceholderText(DESCRIPTION_PLACEHOLDER));
    await user.clear(screen.getByPlaceholderText(BACKGROUND_PLACEHOLDER));
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    // antd reports a cleared field as `''`, and `values.description ?? null` only replaces
    // null/undefined, so the wire value is an empty string rather than null. That is safe: every
    // consumer renders `description || '暂无描述'`, so `''` and null display identically. What
    // actually matters is that the clearing reaches the server at all instead of the echo effect
    // silently restoring 多 Agent 研发协作空间 / 云原生研发 on save.
    expect(bodies[0]).toMatchObject({ description: '', background: '' });
    expect(bodies[0]).not.toMatchObject({
      description: WORKSPACE.description,
      background: WORKSPACE.background,
    });
  });

  it('refuses to submit a blank name', async () => {
    const user = userEvent.setup();
    const error = spyError();
    const bodies = mockUpdate(() => ok({}));
    const { onClose } = renderModal(WORKSPACE);

    await user.clear(await screen.findByPlaceholderText(NAME_PLACEHOLDER));
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    expect(await screen.findByText('工作空间名称不能为空')).toBeInTheDocument();
    expect(bodies).toHaveLength(0);
    expect(onClose).not.toHaveBeenCalled();
    // antd already rendered the field-level message, so a toast on top would be noise.
    expect(error).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('refuses to submit a whitespace-only name', async () => {
    const user = userEvent.setup();
    const bodies = mockUpdate(() => ok({}));
    renderModal({ id: 34, name: '   ', description: '', background: null, version: 1 });

    const nameInput = await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.clear(nameInput);
    await user.type(nameInput, '    ');
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    // F1.4: the name must be non-empty after trimming, so `whitespace: true` is what makes a
    // spaces-only rename fail here rather than on the server.
    expect(await screen.findByText('工作空间名称不能为空')).toBeInTheDocument();
    expect(bodies).toHaveLength(0);
  });

  it('refuses a name longer than 128 characters and a description longer than 512', async () => {
    const bodies = mockUpdate(() => ok({}));
    renderModal(WORKSPACE);

    const nameInput = await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    const descriptionInput = screen.getByPlaceholderText(DESCRIPTION_PLACEHOLDER);
    // The inputs also carry maxLength, which stops typing but not a pasted or autofilled value,
    // so the rules are the backstop that keeps the request off the wire.
    expect(nameInput).toHaveAttribute('maxlength', '128');
    expect(descriptionInput).toHaveAttribute('maxlength', '512');

    fireEvent.change(nameInput, { target: { value: 'a'.repeat(129) } });
    fireEvent.change(descriptionInput, { target: { value: 'b'.repeat(513) } });
    await userEvent.click(screen.getByRole('button', { name: buttonName('保存') }));

    expect(await screen.findByText('工作空间名称不能超过 128 个字符')).toBeInTheDocument();
    expect(await screen.findByText('工作空间描述不能超过 512 个字符')).toBeInTheDocument();
    expect(bodies).toHaveLength(0);
  });

  it('reports a version conflict with a reload hint and keeps the dialog open', async () => {
    const user = userEvent.setup();
    const error = spyError();
    const bodies = mockUpdate(() => fail('11004', '工作空间已被其他人修改'));
    const { onClose } = renderModal(WORKSPACE);

    const nameInput = await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.clear(nameInput);
    await user.type(nameInput, '并发改名');
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    // F1.5: the server must not silently overwrite, and the client must not show the raw
    // conflict text -- the only way forward is to reload the row and edit the fresh version.
    await waitFor(() => expect(error).toHaveBeenCalledWith('工作空间已被其他人修改，请关闭弹窗后重新编辑'));
    expect(onClose).not.toHaveBeenCalled();
    expect(nameInput).toHaveValue('并发改名');
    error.mockRestore();
  });

  it('surfaces the backend message for any other business failure', async () => {
    const user = userEvent.setup();
    const error = spyError();
    mockUpdate(() => fail('11003', '工作空间名称已存在'));
    const { onClose } = renderModal(WORKSPACE);

    const nameInput = await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.clear(nameInput);
    await user.type(nameInput, '重名空间');
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    // F1.5: a duplicate name arrives as a stable business code with a readable message, never as
    // a leaked SQL constraint violation.
    await waitFor(() => expect(error).toHaveBeenCalledWith('工作空间名称已存在'));
    expect(onClose).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('keeps the dialog open and reports a transport failure', async () => {
    const user = userEvent.setup();
    const error = spyError();
    server.use(
      http.put('/api/workspaces/:id', () => HttpResponse.error()),
    );
    const { onClose } = renderModal(WORKSPACE);

    await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    // apiClient normalises every transport failure into an ApiError that carries axios's own
    // wording, so the component's generic fallback is not reachable from here. What this layer
    // does own is the contract below: the operator is told something failed, the dialog
    // survives, and nothing typed so far is thrown away.
    await waitFor(() => expect(error).toHaveBeenCalled());
    expect(error.mock.calls[0][0]).toEqual(expect.any(String));
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByPlaceholderText(NAME_PLACEHOLDER)).toBeInTheDocument();
    error.mockRestore();
  });

  it('invalidates the shared workspaces prefix so the list shows the new name', async () => {
    const user = userEvent.setup();
    spySuccess();
    mockUpdate(() => ok({ name: '星云工坊 2' }));
    const { queryClient } = renderModal(WORKSPACE);
    queryClient.setQueryData(['workspaces', 'mine', 1], [{ id: 31, name: '星云工坊' }]);
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    // F1.3: the card must show the new name and description as soon as the save succeeds, and the
    // prefix (not one exact key) is what also refreshes the discovery tab and the recycle bin.
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workspaces'] }));
    invalidate.mockRestore();
  });

  it('closes on cancel without calling the API', async () => {
    const user = userEvent.setup();
    const bodies = mockUpdate(() => ok({}));
    const { onClose } = renderModal(WORKSPACE);

    await screen.findByPlaceholderText(NAME_PLACEHOLDER);
    await user.click(screen.getByRole('button', { name: buttonName('取消') }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(bodies).toHaveLength(0);
  });
});
