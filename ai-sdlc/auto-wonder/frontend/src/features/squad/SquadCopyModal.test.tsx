import { i18n } from '@/shared/i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SquadCopyModal } from './SquadCopyModal';
import type { Squad, SquadCopyTargetResult } from './api';

const SQUAD: Squad = {
  id: 5,
  name: '开发小队',
  description: '负责研发',
  memberCount: 2,
  gmtCreate: '2026-10-01',
};

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function fail(message: string, code = '35010') {
  return HttpResponse.json(
    { success: false, code, message, traceId: null, data: null },
    { status: 200 },
  );
}

function renderModal(onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN}>
        <SquadCopyModal squad={SQUAD} onClose={onClose} />
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...view, onClose };
}

function stubCheck(
  eligible: boolean,
  missing: { agentId: number; agentName: string }[] = [],
  dependencies: string[] = [],
) {
  server.use(http.get('/api/squads/5/copy-check', () => ok({
    eligible,
    missingOnlineVersions: missing,
    missingDependencies: dependencies,
  })));
}

/** 目标列表桩：记录每次请求的查询串，默认返回 name + description 两个字段。 */
function targetStub(responder: (keyword: string, page: number) => { list: unknown[]; total: number }) {
  const calls: { keyword: string; page: number }[] = [];
  server.use(http.get('/api/squads/5/copy-targets', ({ request }) => {
    const url = new URL(request.url);
    const keyword = url.searchParams.get('keyword') ?? '';
    const page = Number(url.searchParams.get('page') ?? '1');
    calls.push({ keyword, page });
    return ok({ ...responder(keyword, page), pageNum: page, pageSize: 10 });
  }));
  return calls;
}

function resultRow(workspaceId: number) {
  return screen.getByTestId(`copy-result-${workspaceId}`);
}

async function findDialogByTitle(title: string): Promise<HTMLElement> {
  const titleNode = await screen.findByText(title, { selector: '.ant-modal-title' });
  const modal = titleNode.closest('[role="dialog"]');
  expect(modal).not.toBeNull();
  const wrap = (modal as HTMLElement).closest('.ant-modal-wrap') as HTMLElement;
  await waitFor(() => { expect(wrap.style.display).not.toBe('none'); });
  return modal as HTMLElement;
}

describe('SquadCopyModal', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    vi.restoreAllMocks();
  });

  it('blocks the copy flow and lists agents without an online version', async () => {
    // 旧服务端响应没有 missingDependencies 字段，冻结块必须照常渲染而不是崩溃。
    server.use(http.get('/api/squads/5/copy-check', () => ok({
      eligible: false,
      missingOnlineVersions: [{ agentId: 9, agentName: '后端数字人' }, { agentId: 10, agentName: '前端数字人' }],
    })));
    let targetRequests = 0;
    server.use(http.get('/api/squads/5/copy-targets', () => {
      targetRequests += 1;
      return ok({ list: [], total: 0, pageNum: 1, pageSize: 10 });
    }));

    renderModal();

    expect(await screen.findByText('以下数字员工没有在线版本，无法复制')).toBeInTheDocument();
    const list = screen.getByTestId('copy-blocked-list');
    expect(within(list).getByText('后端数字人')).toBeInTheDocument();
    expect(within(list).getByText('前端数字人')).toBeInTheDocument();
    expect(screen.queryByTestId('copy-blocked-dependencies')).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '搜索工作空间名称' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '确定复制' })).not.toBeInTheDocument();
    expect(targetRequests).toBe(0);
  });

  it('blocks the copy flow and lists broken references with readable kinds', async () => {
    stubCheck(false, [], [
      'agent#40474',
      'skill#3',
      'plugin#legacy-tool',
      'environment_variable#7',
      'sdlc#30',
      'mystery#1',
    ]);
    let targetRequests = 0;
    server.use(http.get('/api/squads/5/copy-targets', () => {
      targetRequests += 1;
      return ok({ list: [], total: 0, pageNum: 1, pageSize: 10 });
    }));

    renderModal();

    expect(await screen.findByText('小队存在已失效的关联，无法复制')).toBeInTheDocument();
    const list = screen.getByTestId('copy-blocked-dependencies-list');
    expect(within(list).getByText('数字员工 #40474')).toBeInTheDocument();
    expect(within(list).getByText('能力 #3')).toBeInTheDocument();
    expect(within(list).getByText('插件 legacy-tool')).toBeInTheDocument();
    expect(within(list).getByText('环境变量 #7')).toBeInTheDocument();
    expect(within(list).getByText('SDLC 流程 #30')).toBeInTheDocument();
    // 未知种类保留服务端原样引用，不静默丢弃
    expect(within(list).getByText('mystery#1')).toBeInTheDocument();
    expect(screen.queryByTestId('copy-blocked-list')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '确定复制' })).not.toBeInTheDocument();
    expect(targetRequests).toBe(0);
  });

  it('lists both version blocks and broken references when both block the copy', async () => {
    stubCheck(false, [{ agentId: 9, agentName: '后端数字人' }], ['agent#40474']);

    renderModal();

    expect(await screen.findByText('以下数字员工没有在线版本，无法复制')).toBeInTheDocument();
    expect(screen.getByTestId('copy-blocked-list')).toHaveTextContent('后端数字人');
    expect(screen.getByTestId('copy-blocked-dependencies-list')).toHaveTextContent('数字员工 #40474');
    expect(screen.queryByRole('button', { name: '确定复制' })).not.toBeInTheDocument();
  });

  it('offers a re-check when the pre-check request fails', async () => {
    let attempt = 0;
    server.use(http.get('/api/squads/5/copy-check', () => {
      attempt += 1;
      return attempt === 1 ? fail('检查服务暂不可用') : ok({ eligible: true, missingOnlineVersions: [] });
    }));
    targetStub(() => ({ list: [{ id: 2, name: '目标空间', description: '' }], total: 1 }));

    renderModal();

    expect(await screen.findByText('复制前置检查失败')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '重新检查' }));

    expect(await screen.findByText('目标空间')).toBeInTheDocument();
  });

  it('keeps confirm disabled until a target is selected', async () => {
    stubCheck(true);
    targetStub(() => ({
      list: [
        { id: 2, name: '目标空间A', description: '说明A' },
        { id: 3, name: '目标空间B', description: '' },
      ],
      total: 2,
    }));

    renderModal();

    expect(await screen.findByText('共 2 个可选工作空间')).toBeInTheDocument();
    expect(screen.getByText('说明A')).toBeInTheDocument();
    expect(screen.getByText('暂无描述')).toBeInTheDocument();

    const confirm = screen.getByRole('button', { name: '确定复制' });
    expect(confirm).toBeDisabled();

    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间A' }));
    expect(screen.getByRole('button', { name: '确定复制' })).toBeEnabled();
    expect(screen.getByTestId('copy-selected-summary')).toHaveTextContent('已选 1 个目标');
  });

  it('keeps selections across pages and search, and lets tags remove them', async () => {
    stubCheck(true);
    const calls = targetStub((keyword, page) => {
      if (keyword) {
        return { list: [{ id: 9, name: '搜索命中', description: '' }], total: 1 };
      }
      return page === 1
        ? { list: [{ id: 2, name: '目标空间A', description: '' }], total: 11 }
        : { list: [{ id: 3, name: '目标空间B', description: '' }], total: 11 };
    });

    renderModal();

    expect(await screen.findByText('目标空间A')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间A' }));

    await userEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('目标空间B')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间B' }));
    expect(screen.getByTestId('copy-selected-summary')).toHaveTextContent('已选 2 个目标');

    // 搜索变化回到第一页，已选目标仍然保留
    await userEvent.type(screen.getByRole('textbox', { name: '搜索工作空间名称' }), '搜索');
    expect(await screen.findByText('搜索命中')).toBeInTheDocument();
    await waitFor(() => { expect(calls.some((call) => call.keyword === '搜索' && call.page === 1)).toBe(true); });
    expect(screen.getByTestId('copy-selected-summary')).toHaveTextContent('已选 2 个目标');

    // 取消单个已选目标
    const tag = within(screen.getByTestId('copy-selected-summary')).getByText('目标空间A').closest('.ant-tag') as HTMLElement;
    await userEvent.click(tag.querySelector('.ant-tag-close-icon') as HTMLElement);
    expect(screen.getByTestId('copy-selected-summary')).toHaveTextContent('已选 1 个目标');
  });

  it('shows the empty hint when a keyword matches nothing', async () => {
    stubCheck(true);
    targetStub((keyword) => (keyword
      ? { list: [], total: 0 }
      : { list: [{ id: 2, name: '目标空间A', description: '' }], total: 1 }));

    renderModal();
    await screen.findByText('目标空间A');

    await userEvent.type(screen.getByRole('textbox', { name: '搜索工作空间名称' }), '不存在的空间');
    expect(await screen.findByText('没有名称匹配“不存在的空间”的工作空间')).toBeInTheDocument();
  });

  it('cancelling the confirmation dialog has no side effect', async () => {
    stubCheck(true);
    targetStub(() => ({ list: [{ id: 2, name: '目标空间A', description: '' }], total: 1 }));
    let copyCalls = 0;
    server.use(http.post('/api/squads/5/copy', () => {
      copyCalls += 1;
      return ok({ requestId: 'r1', results: [] });
    }));

    renderModal();
    await screen.findByText('目标空间A');
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间A' }));
    await userEvent.click(screen.getByRole('button', { name: '确定复制' }));

    const confirm = await findDialogByTitle('确认复制到工作空间');
    expect(within(confirm).getByText('源小队：开发小队')).toBeInTheDocument();
    expect(within(confirm).getByText('目标空间A')).toBeInTheDocument();
    expect(within(confirm).getByText('不复制仓库绑定；目标数字人的仓库权限为空')).toBeInTheDocument();
    expect(within(confirm).getByText('目标数字人直接上线')).toBeInTheDocument();
    expect(within(confirm).getByText('执行器以新凭证初始离线，需在目标环境重新启动')).toBeInTheDocument();

    await userEvent.click(within(confirm).getByRole('button', { name: /取\s*消/ }));

    await waitFor(() => {
      const wrap = confirm.closest('.ant-modal-wrap') as HTMLElement;
      expect(wrap.style.display).toBe('none');
    });
    expect(copyCalls).toBe(0);
    expect(screen.getByTestId('copy-selected-summary')).toHaveTextContent('已选 1 个目标');
  });

  it('submits the selected targets and shows per-target results with links and reasons', async () => {
    stubCheck(true);
    targetStub(() => ({
      list: [
        { id: 2, name: '目标空间A', description: '' },
        { id: 3, name: '目标空间B', description: '' },
      ],
      total: 2,
    }));
    const bodies: { requestId: string; targetWorkspaceIds: number[] }[] = [];
    server.use(http.post('/api/squads/5/copy', async ({ request }) => {
      const body = await request.json() as { requestId: string; targetWorkspaceIds: number[] };
      bodies.push(body);
      const results: SquadCopyTargetResult[] = body.targetWorkspaceIds.map((id) => (id === 2
        ? { workspaceId: 2, workspaceName: '目标空间A', success: true, squadId: 15, errorCode: null, message: null }
        : { workspaceId: 3, workspaceName: '目标空间B', success: false, squadId: null, errorCode: '35006', message: '复制依赖无法解析：skill#3' }));
      return ok({ requestId: body.requestId, results });
    }));

    renderModal();
    await screen.findByText('目标空间A');
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间A' }));
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间B' }));
    await userEvent.click(screen.getByRole('button', { name: '确定复制' }));
    const confirm = await findDialogByTitle('确认复制到工作空间');
    await userEvent.click(within(confirm).getByRole('button', { name: '确定复制' }));

    expect(await screen.findByText('复制结果')).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
    expect(bodies[0].targetWorkspaceIds.sort()).toEqual([2, 3]);
    expect(bodies[0].requestId).toBeTruthy();

    const successRow = resultRow(2);
    expect(within(successRow).getByText('复制成功')).toBeInTheDocument();
    expect(within(successRow).getByRole('link', { name: '查看小队 #15' }))
      .toHaveAttribute('href', '/agents?tab=squads&squadId=15&workspaceId=2');

    const failedRow = resultRow(3);
    expect(within(failedRow).getByText('复制失败')).toBeInTheDocument();
    expect(within(failedRow).getByText('复制依赖无法解析：skill#3')).toBeInTheDocument();
    expect(screen.getByText('仅重试所选失败目标，成功副本不会重复创建')).toBeInTheDocument();
  });

  it('retries only the selected failed targets and merges the new results', async () => {
    stubCheck(true);
    targetStub(() => ({
      list: [
        { id: 2, name: '目标空间A', description: '' },
        { id: 3, name: '目标空间B', description: '' },
        { id: 4, name: '目标空间C', description: '' },
      ],
      total: 3,
    }));
    const bodies: { requestId: string; targetWorkspaceIds: number[] }[] = [];
    server.use(http.post('/api/squads/5/copy', async ({ request }) => {
      const body = await request.json() as { requestId: string; targetWorkspaceIds: number[] };
      bodies.push(body);
      if (bodies.length === 1) {
        return ok({
          requestId: body.requestId,
          results: body.targetWorkspaceIds.map((id) => (id === 2
            ? { workspaceId: 2, workspaceName: '目标空间A', success: true, squadId: 15, errorCode: null, message: null }
            : {
              workspaceId: id,
              workspaceName: id === 3 ? '目标空间B' : '目标空间C',
              success: false,
              squadId: null,
              errorCode: '35006',
              message: id === 3 ? '复制依赖无法解析：skill#3' : '复制依赖无法解析：env#7',
            })) as SquadCopyTargetResult[],
        });
      }
      return ok({
        requestId: body.requestId,
        results: body.targetWorkspaceIds.map((id) => ({
          workspaceId: id,
          workspaceName: '目标空间B',
          success: true,
          squadId: 33,
          errorCode: null,
          message: null,
        })),
      });
    }));

    renderModal();
    await screen.findByText('目标空间A');
    for (const name of ['目标空间A', '目标空间B', '目标空间C']) {
      await userEvent.click(screen.getByRole('checkbox', { name }));
    }
    await userEvent.click(screen.getByRole('button', { name: '确定复制' }));
    const firstConfirm = await findDialogByTitle('确认复制到工作空间');
    await userEvent.click(within(firstConfirm).getByRole('button', { name: '确定复制' }));
    await screen.findByText('复制结果');

    await userEvent.click(screen.getByRole('checkbox', { name: '选择重试 目标空间B' }));
    await userEvent.click(screen.getByRole('button', { name: /重试所选目标/ }));

    const retryConfirm = await findDialogByTitle('确认复制到工作空间');
    expect(within(retryConfirm).getByText('目标空间B')).toBeInTheDocument();
    expect(within(retryConfirm).queryByText('目标空间A')).not.toBeInTheDocument();
    await userEvent.click(within(retryConfirm).getByRole('button', { name: '确定复制' }));

    await waitFor(() => { expect(bodies).toHaveLength(2); });
    expect(bodies[1].targetWorkspaceIds).toEqual([3]);
    expect(bodies[1].requestId).not.toBe(bodies[0].requestId);
    expect(bodies.some((body) => body.targetWorkspaceIds.includes(2))).toBe(true);
    expect(bodies[1].targetWorkspaceIds).not.toContain(2);

    await waitFor(() => {
      expect(within(resultRow(3)).getByText('复制成功')).toBeInTheDocument();
    });
    expect(within(resultRow(2)).getByText('复制成功')).toBeInTheDocument();
    expect(within(resultRow(4)).getByText('复制失败')).toBeInTheDocument();
  });

  it('keeps the same request id when the same confirmation is resubmitted after a failure', async () => {
    stubCheck(true);
    targetStub(() => ({ list: [{ id: 2, name: '目标空间A', description: '' }], total: 1 }));
    const bodies: { requestId: string }[] = [];
    server.use(http.post('/api/squads/5/copy', async ({ request }) => {
      const body = await request.json() as { requestId: string };
      bodies.push(body);
      if (bodies.length === 1) {
        return fail('复制请求暂时不可用');
      }
      return ok({
        requestId: body.requestId,
        results: [{ workspaceId: 2, workspaceName: '目标空间A', success: true, squadId: 15, errorCode: null, message: null }],
      });
    }));

    renderModal();
    await screen.findByText('目标空间A');
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间A' }));
    await userEvent.click(screen.getByRole('button', { name: '确定复制' }));
    const confirm = await findDialogByTitle('确认复制到工作空间');
    await userEvent.click(within(confirm).getByRole('button', { name: '确定复制' }));

    expect(await within(confirm).findByText('复制请求暂时不可用')).toBeInTheDocument();

    await userEvent.click(within(confirm).getByRole('button', { name: '确定复制' }));
    await screen.findByText('复制结果');
    expect(bodies).toHaveLength(2);
    expect(bodies[1].requestId).toBe(bodies[0].requestId);
  });

  it('disables the confirmation dialog while the copy is running', async () => {
    stubCheck(true);
    targetStub(() => ({ list: [{ id: 2, name: '目标空间A', description: '' }], total: 1 }));
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.post('/api/squads/5/copy', async () => {
      await gate;
      return ok({
        requestId: 'r1',
        results: [{ workspaceId: 2, workspaceName: '目标空间A', success: true, squadId: 15, errorCode: null, message: null }],
      });
    }));

    renderModal();
    await screen.findByText('目标空间A');
    await userEvent.click(screen.getByRole('checkbox', { name: '目标空间A' }));
    await userEvent.click(screen.getByRole('button', { name: '确定复制' }));
    const confirm = await findDialogByTitle('确认复制到工作空间');
    await userEvent.click(within(confirm).getByRole('button', { name: '确定复制' }));

    expect(await within(confirm).findByLabelText('正在复制，请勿关闭')).toBeInTheDocument();
    expect(within(confirm).getByRole('button', { name: /取\s*消/ })).toBeDisabled();

    await act(async () => { release?.(); });
    expect(await screen.findByText('复制结果')).toBeInTheDocument();
  });
});
