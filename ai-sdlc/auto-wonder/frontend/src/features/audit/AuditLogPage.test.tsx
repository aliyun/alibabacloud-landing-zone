import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AuditLogPage } from './AuditLogPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><AuditLogPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AuditLogPage', () => {
  it('renders audit log table', async () => {
    server.use(
      http.get('/api/audit-logs', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 1, module: 'DISPATCH', action: 'RUNTIME_EVENT', actorId: 10, actorType: 'AGENT', actorName: 'auto-dev', targetType: 'dispatch', targetId: '42', detail: null, detailJson: '{"triggerType":"EVENT","triggerSource":"runtime.progress","eventType":"step.started","stepName":"编码实现","message":"开始实现"}', gmtCreate: '2026-07-01T10:00:00Z' }],
        });
      }),
      http.get('/api/audit-logs/count', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: 1,
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('auto-dev (AGENT)')).toBeInTheDocument();
    expect(screen.getByText('运行事件')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    expect(screen.getByText('EVENT / runtime.progress')).toBeInTheDocument();
    expect(screen.getByText('step.started')).toBeInTheDocument();

  });

  it('renders rows when audit log list data is nested but count succeeds', async () => {
    server.use(
      http.get('/api/audit-logs', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            data: [
              {
                id: 14474,
                actorId: 10000,
                actorType: 'HUMAN',
                actorName: '蔡何',
                module: 'WORKSPACES',
                action: 'CREATE_WORKSPACES_ID_SWITCH',
                targetType: 'workspaces',
                targetId: 10002,
                detailJson: '{"path":"/api/workspaces/10002/switch","method":"POST","status":200,"success":true,"actorType":"HUMAN","eventType":"http.post","triggerType":"ACTIVE","triggerSource":"USER_CLICK"}',
                gmtCreate: '2026-07-19T13:40:19.267+00:00',
              },
              {
                id: 14471,
                actorId: 10001,
                actorType: 'HUMAN',
                actorName: 'lazy',
                module: 'WORKITEM',
                action: 'CREATE_WORKITEMS_ID_TRANSITION',
                targetType: 'workitem',
                targetId: 10664,
                detailJson: '{"path":"/api/workitems/10664/transition","method":"POST","status":200,"success":true,"actorType":"HUMAN","eventType":"http.post","triggerType":"ACTIVE","triggerSource":"USER_CLICK"}',
                gmtCreate: '2026-07-18T02:15:05.588+00:00',
              },
            ],
            total: 2,
          },
          traceId: '07a8cfbc-5c76-48aa-83ea-53aad1d6f7f0',
        });
      }),
      http.get('/api/audit-logs/count', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: 2,
          traceId: null,
        });
      }),
    );

    renderPage();

    expect(await screen.findByText('蔡何 (HUMAN)')).toBeInTheDocument();
    expect(screen.getByText('CREATE_WORKSPACES_ID_SWITCH')).toBeInTheDocument();
    expect(screen.getByText('lazy (HUMAN)')).toBeInTheDocument();
    expect(screen.getByText('CREATE_WORKITEMS_ID_TRANSITION')).toBeInTheDocument();
    expect(screen.queryByText('ACTIVE / USER_CLICK')).not.toBeInTheDocument();
    expect(screen.getByText('共 2 条')).toBeInTheDocument();
  });

  it('renders rows when detailJson embeds a big integer', async () => {
    // Real audit payload: detailJson is a string whose JSON contains a 19-digit checkpointSeq.
    // The shared client big-int guard must not corrupt digits inside string values.
    const detailJson =
      '{"provider":"codex","checkpointSeq":1784292150410807000,"triggerType":"EVENT","triggerSource":"DAEMON_CALLBACK","eventType":"daemon.checkpoint","message":"上传检查点"}';
    server.use(
      http.get('/api/audit-logs', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 14451, module: 'DISPATCH', action: 'UPLOAD_CHECKPOINT', actorId: 40015, actorType: 'AGENT', actorName: 'AW测试工程师', targetType: 'dispatch', targetId: '10330', detail: null, detailJson, gmtCreate: '2026-07-17T12:42:31Z' }],
        });
      }),
      http.get('/api/audit-logs/count', () => {
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: 1 });
      }),
    );
    renderPage();
    expect(await screen.findByText('AW测试工程师 (AGENT)')).toBeInTheDocument();
    expect(screen.getByText('UPLOAD_CHECKPOINT')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    expect(screen.getByText('EVENT / DAEMON_CALLBACK')).toBeInTheDocument();
    expect(screen.getByText('daemon.checkpoint')).toBeInTheDocument();
  });

  it('filters both rows and count by actor type, and resets to human operations', async () => {
    const seen: Record<string, string | null> = {};
    server.use(...['/api/audit-logs', '/api/audit-logs/count'].map((path) => http.get(path, ({ request }) => {
      seen[path] = new URL(request.url).searchParams.get('actorType');
      return HttpResponse.json({ success: true, code: '0', data: path.endsWith('count') ? 0 : [] });
    })));
    const user = userEvent.setup();
    renderPage();
    const check = async (expected: string | null) => waitFor(() => {
      expect(seen['/api/audit-logs']).toBe(expected);
      expect(seen['/api/audit-logs/count']).toBe(expected);
    });
    await check('HUMAN');
    await user.click(screen.getByText('全部', { exact: true }));
    await check(null);
    await user.click(screen.getByText('数字员工', { selector: '.ant-segmented-item-label' }));
    await check('AGENT');
    await user.click(screen.getByRole('button', { name: /重\s*置/ }));
    expect(screen.getByRole('radio', { name: '人工操作' })).toBeChecked();
  });

  it('renders multi-dimensional filters and submits selected params', async () => {
    const captured = new URLSearchParams();
    server.use(
      http.get('/api/audit-logs', ({ request }) => {
        const url = new URL(request.url);
        url.searchParams.forEach((value, key) => captured.set(key, value));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: [],
        });
      }),
      http.get('/api/audit-logs/count', ({ request }) => {
        const url = new URL(request.url);
        url.searchParams.forEach((value, key) => captured.set(key, value));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: 23,
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByPlaceholderText('按操作人 ID 筛选')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('按目标 ID 筛选')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /搜\s*索/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重\s*置/ })).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('按操作人 ID 筛选'), '7');
    await user.type(screen.getByPlaceholderText('按目标 ID 筛选'), '42');
    await user.type(screen.getByPlaceholderText('搜索详情关键词'), '发布');

    const comboboxes = screen.getAllByRole('combobox');
    await user.click(comboboxes[0]);
    await user.click(await screen.findByText('技能'));
    await user.click(comboboxes[1]);
    await user.click(await screen.findByText('删除'));
    await user.click(comboboxes[2]);
    const workitemOptions = await screen.findAllByText('工单');
    await user.click(workitemOptions[workitemOptions.length - 1]);
    await user.click(comboboxes[3]);
    await user.click(await screen.findByText('近 7 天'));
    await user.click(screen.getByRole('button', { name: /搜\s*索/ }));

    await waitFor(() => {
      expect(captured.get('module')).toBe('SKILL');
      expect(captured.get('action')).toBe('DELETE');
      expect(captured.get('targetType')).toBe('workitem');
      expect(captured.get('actorId')).toBe('7');
      expect(captured.get('targetId')).toBe('42');
      expect(captured.get('keyword')).toBe('发布');
      expect(captured.get('startTime')).toBeTruthy();
      expect(captured.get('endTime')).toBeTruthy();
      expect(new Date(captured.get('startTime') ?? '').getTime()).toBeLessThan(new Date(captured.get('endTime') ?? '').getTime());
    });

    expect(await screen.findByText('共 23 条')).toBeInTheDocument();
  });
});
