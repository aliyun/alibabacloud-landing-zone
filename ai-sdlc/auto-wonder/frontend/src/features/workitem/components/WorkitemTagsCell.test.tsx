import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemTagsCell } from './WorkitemTagsCell';
import type { Workitem } from '@/shared/types/workitem';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

function workitem(partial: Partial<Workitem> = {}): Workitem {
  return {
    id: 500,
    workType: 'REQ',
    title: '标签工单',
    statusCategory: 'IN_PROGRESS',
    statusName: '开发中',
    priority: 2,
    tags: [],
    ...partial,
  } as Workitem;
}

function renderCell(item: Workitem) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemTagsCell workitem={item} />
    </QueryClientProvider>,
  );
}

describe('WorkitemTagsCell', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: [] });
  });

  afterEach(() => {
    message.destroy();
    useAuthStore.getState().clear();
  });

  it('shows the tags and loads workspace suggestions', async () => {
    let optionsRequested = 0;
    server.use(http.get('/api/workitems/tag-options', () => {
      optionsRequested += 1;
      return ok(['发布', '线上', '后端']);
    }));

    renderCell(workitem({ tags: ['发布', '线上', '后端'] }));

    expect(screen.getByTestId('workitem-tags-cell-500')).toBeInTheDocument();
    expect(screen.getByText('发布')).toBeInTheDocument();
    expect(screen.getByTestId('workitem-tags-overflow')).toHaveTextContent('+1');
    await waitFor(() => expect(optionsRequested).toBeGreaterThan(0));
  });

  it('edits tags in a popover and closes it after saving', async () => {
    const user = userEvent.setup();
    let body: { tags?: string[] } | null = null;
    server.use(
      http.get('/api/workitems/tag-options', () => ok(['发布', '线上', '后端'])),
      http.put('/api/workitems/500/tags', async ({ request }) => {
        body = await request.json() as { tags?: string[] };
        return ok(workitem({ tags: body.tags }));
      }),
    );

    renderCell(workitem({ tags: ['发布'] }));
    await user.click(screen.getByRole('button', { name: '编辑标签' }));

    const editor = await screen.findByTestId('workitem-tags-editor');
    await user.type(within(editor).getByRole('combobox'), '线上');
    fireEvent.keyDown(within(editor).getByRole('combobox'), { key: 'Enter', keyCode: 13, which: 13 });
    await waitFor(() => expect(within(editor).getByText('线上', { selector: '.ant-select-selection-item-content' })).toBeInTheDocument());
    await user.click(within(editor).getByRole('button', { name: '保存' }));

    await waitFor(() => expect(body).toMatchObject({ tags: ['发布', '线上'] }));
    await waitFor(() => expect(screen.queryByTestId('workitem-tags-editor')).not.toBeInTheDocument());
  });

  it('stops click propagation so card navigation is not triggered', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <div onClick={onClick}>
          <WorkitemTagsCell workitem={workitem({ tags: ['发布'] })} />
        </div>
      </QueryClientProvider>,
    );

    await user.click(screen.getByText('发布'));
    await user.click(screen.getByRole('button', { name: '编辑标签' }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('offers no writable entry and only prompts when a read-only member clicks the tags', async () => {
    const user = userEvent.setup();
    let putCalls = 0;
    server.use(http.put('/api/workitems/500/tags', () => {
      putCalls += 1;
      return ok(workitem());
    }));
    useAuthStore.setState({ accessLevel: 'READ_ONLY' });

    renderCell(workitem({ tags: ['发布'] }));
    expect(screen.queryByRole('button', { name: '编辑标签' })).not.toBeInTheDocument();

    await user.click(screen.getByText('发布'));

    expect(await screen.findByText(/编辑标签需要读写权限/)).toBeInTheDocument();
    expect(putCalls).toBe(0);
    expect(screen.queryByTestId('workitem-tags-editor')).not.toBeInTheDocument();
  });

  it('keeps the editor open and skips the write when permission is revoked before saving', async () => {
    const user = userEvent.setup();
    let putCalls = 0;
    server.use(http.put('/api/workitems/500/tags', () => {
      putCalls += 1;
      return ok(workitem());
    }));

    renderCell(workitem({ tags: ['发布'] }));
    await user.click(screen.getByRole('button', { name: '编辑标签' }));
    const editor = await screen.findByTestId('workitem-tags-editor');

    useAuthStore.setState({ accessLevel: 'READ_ONLY', readOnlyDomains: [] });
    await user.click(within(editor).getByRole('button', { name: '保存' }));

    expect(await screen.findByText(/编辑标签需要读写权限/)).toBeInTheDocument();
    expect(putCalls).toBe(0);
    expect(screen.getByTestId('workitem-tags-editor')).toBeInTheDocument();
  });

  it('treats a read-only workitem domain as read-only for the tag entry', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: ['WORKITEM'] });

    renderCell(workitem({ tags: ['发布'] }));
    expect(screen.queryByRole('button', { name: '编辑标签' })).not.toBeInTheDocument();

    await user.click(screen.getByText('发布'));

    expect(await screen.findByText(/「工单」对当前角色为只读/)).toBeInTheDocument();
    expect(screen.queryByTestId('workitem-tags-editor')).not.toBeInTheDocument();
  });

  it('lets admins edit even when the workitem domain is read-only', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ accessLevel: 'ADMIN', readOnlyDomains: ['WORKITEM'] });

    renderCell(workitem({ tags: ['发布'] }));
    await user.click(screen.getByRole('button', { name: '编辑标签' }));

    expect(await screen.findByTestId('workitem-tags-editor')).toBeInTheDocument();
  });

  it('shows a dash placeholder for untagged workitems and opens the editor from it', async () => {
    const user = userEvent.setup();
    renderCell(workitem({ tags: [] }));

    const cell = screen.getByTestId('workitem-tags-cell-500');
    const placeholder = within(cell).getByText('-');
    expect(placeholder).toBeInTheDocument();

    await user.click(placeholder);
    expect(await screen.findByTestId('workitem-tags-editor')).toBeInTheDocument();
  });
});
