import { i18n } from '@/shared/i18n';
import { message } from 'antd';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SdlcDetailPage, RetryBudgetTooltipContent } from './SdlcDetailPage';
import { useAuthStore } from '@/shared/auth/store';

function renderPage(initialPath = '/sdlcs/1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes><Route path="/sdlcs/:id" element={<SdlcDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  server.use(http.get('/api/workspaces/current/members', () => HttpResponse.json({ success: true, data: [] })));
});

describe('SdlcDetailPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('defaults approvers to the creator and saves multiple members and identity tags', async () => {
    let saved: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/sdlcs/1', () => HttpResponse.json({ success: true, data: {
        id: '1', name: '审批人配置', status: 'DRAFT', steps: [{ id: '10', stepOrder: 1, name: '编写摘要',
          kind: 'analysis', required: true, instructionMd: '编写验收摘要', gatePolicyJson: '{"humanApprovalRequired":true}' }],
      } })),
      http.get('/api/workspaces/current/members', () => HttpResponse.json({ success: true, data: [
        { userId: 2, nickname: '审批成员甲', username: 'reviewer-a', accessLevel: 'READ_WRITE', identityTags: ['验收'] },
        { userId: 3, nickname: '只读成员', username: 'reader', accessLevel: 'READ_ONLY', identityTags: [] },
      ] })),
      http.put('/api/sdlcs/1/steps/10', async ({ request }) => {
        saved = await request.json() as Record<string, unknown>; return HttpResponse.json({ success: true, data: { id: '10' } });
      }),
    );
    const { container } = renderPage();
    await screen.findByText('审批人配置');
    fireEvent.click(container.querySelector('.ant-table-row .anticon-edit')!.closest('button')!);
    expect(await screen.findByText('工单创建人（建单后确定）')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '审批人' }));
    fireEvent.click(await screen.findByText('审批成员甲'));
    fireEvent.click(await screen.findByText('身份标签：验收'));
    expect(screen.queryByText('只读成员')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /OK|确\s*定/ }));
    await waitFor(() => expect(saved).toBeDefined());
    expect(JSON.parse(saved!.gatePolicyJson as string)).toEqual({ humanApprovalRequired: true,
      humanApprovers: { includeCreator: true, userIds: [2], identityTags: ['验收'] } });
  });

  it('keeps invalid step confirmation inline with the raw instruction and no POST', async () => {
    let posts = 0;
    server.use(http.get('/api/sdlcs/1', () => HttpResponse.json({ success: true, data: { id: '1', name: 'raw', status: 'DRAFT', steps: [] } })), http.post('/api/sdlcs/1/steps', () => { posts++; return HttpResponse.json({ success: true, data: {} }); }));
    renderPage(); await screen.findByText('raw');
    fireEvent.click(screen.getByRole('button', { name: /添加步骤/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('执行说明'), { target: { value: '<raw 中文>' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /OK|确\s*定/ }));
    expect(await screen.findByText('请输入步骤名称')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('执行说明')).toHaveValue('<raw 中文>');
    expect(screen.getByRole('dialog')).toBe(dialog); expect(posts).toBe(0);
  });

  it('renders step chain with editor controls', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '标准流程', description: '标准开发流程', status: 'DRAFT',
            workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis', instructionMd: '理解需求并判断是否可实现', checklistJson: '["确认边界"]', gatePolicyJson: null, required: true, timeoutSeconds: 600, retryBudget: 1 },
              { id: '11', sdlcId: '1', stepOrder: 2, name: '代码实现', kind: 'implementation', instructionMd: '基于 worktree 完成编码实现', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
              { id: '12', sdlcId: '1', stepOrder: 3, name: '测试验证', kind: 'test', instructionMd: '验证交付质量', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('标准流程')).toBeInTheDocument();
    expect(screen.getAllByText('需求分析').length).toBeGreaterThan(0);
    expect(screen.getAllByText('代码实现').length).toBeGreaterThan(0);
    expect(screen.getByText('步骤概览')).toBeInTheDocument();
    expect(screen.getByLabelText('步骤 1: 需求分析')).toBeInTheDocument();
    expect(screen.getByLabelText('步骤 2: 代码实现')).toBeInTheDocument();
    expect(screen.getByLabelText('步骤 3: 测试验证')).toBeInTheDocument();
    expect(screen.getByTestId('sdlc-step-overview-flow')).toHaveStyle({
      flexWrap: 'wrap',
      overflowX: 'visible',
    });
    expect(screen.getByTestId('sdlc-step-overview-card')).toHaveStyle({
      boxShadow: 'none',
    });
    expect(screen.getAllByLabelText('下一步骤').length).toBe(2);
    expect(screen.getByText('添加步骤')).toBeInTheDocument();
    expect(screen.getByText('步骤链编辑器')).toBeInTheDocument();
    expect(screen.getAllByText('执行说明').length).toBeGreaterThan(0);
    expect(screen.queryByText('角色码')).not.toBeInTheDocument();
  });

  it('hides the AI-assisted design entry from the detail editor', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '标准流程', description: '标准开发流程', status: 'DRAFT',
            workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis', instructionMd: '理解需求并判断是否可实现', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );
    renderPage();

    await screen.findByText('标准流程');

    expect(screen.queryByRole('button', { name: /AI 辅助/ })).not.toBeInTheDocument();
    expect(screen.queryByText('AI 辅助设计 SDLC')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /添加步骤/ })).toBeInTheDocument();
  });

  it('shows locked state when ENABLED', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '已上线流程', description: '', status: 'ENABLED',
            workType: null, isDefault: 0, entryStepId: null, version: 2,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '开发', kind: 'implementation', instructionMd: '完成开发', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('已上线流程')).toBeInTheDocument();
    expect(screen.getByText(/模版已启用/)).toBeInTheDocument();
    expect(screen.getByText('已启用')).toBeInTheDocument();
    expect(screen.queryByText('ENABLED')).not.toBeInTheDocument();
  });

  it.each([
    ['DRAFT', '草稿'],
    ['ENABLED', '已启用'],
    ['DISABLED', '已禁用'],
  ])('displays Chinese label "%s" for status "%s"', async (status, label) => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '状态测试流程', description: '', status,
            workType: null, isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('状态测试流程')).toBeInTheDocument();
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.queryByText(status)).not.toBeInTheDocument();
  });

  it('keeps large snowflake id exact when loading detail', async () => {
    const largeId = '334208147726012416';
    let requestedPath = '';
    server.use(
      http.get(`/api/sdlcs/${largeId}`, ({ request }) => {
        requestedPath = new URL(request.url).pathname;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: largeId, name: '大 ID 流程', description: '', status: 'DRAFT',
            workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );

    renderPage(`/sdlcs/${largeId}`);

    expect(await screen.findByText('大 ID 流程')).toBeInTheDocument();
    expect(requestedPath).toBe(`/api/sdlcs/${largeId}`);
  });

  it('shows an empty overview when the SDLC has no steps', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '空流程', description: '', status: 'DRAFT',
            workType: 'TASK', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );

    renderPage();

    expect(await screen.findByText('空流程')).toBeInTheDocument();
    expect(screen.getByText('暂无步骤，添加后将在这里形成流程概览')).toBeInTheDocument();
  });

  it('surfaces the server error message when saving a step fails', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '错误提示流程', description: '', status: 'DRAFT',
            workType: 'BUG', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis', instructionMd: '理解需求', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
      http.put('/api/sdlcs/1/steps/10', () => {
        return HttpResponse.json({
          success: false, code: '10001', message: 'checklistJson 不是合法的 JSON',
          traceId: null, data: null,
        }, { status: 400 });
      }),
    );

    const { container } = renderPage();
    expect(await screen.findByText('错误提示流程')).toBeInTheDocument();

    const editButton = container.querySelector('.ant-table-row .anticon-edit')?.closest('button');
    expect(editButton).toBeTruthy();
    fireEvent.click(editButton!);

    const okButton = await screen.findByRole('button', { name: /OK|确\s*定/ });
    fireEvent.click(okButton);

    expect(await screen.findByText('checklistJson 不是合法的 JSON')).toBeInTheDocument();
  });

  it.each([true, false])('sets human approval to %s while preserving other policy and transition fields', async (enabled) => {
    let capturedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '流转字段流程', description: '', status: 'DRAFT',
            workType: 'BUG', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              {
                id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis',
                instructionMd: '理解需求', checklistJson: null,
                gatePolicyJson: JSON.stringify({ evidenceRequired: true, humanApprovalRequired: !enabled }),
                required: true, timeoutSeconds: 600, retryBudget: 2,
                code: 'STEP_1', handlerType: 'AGENT', handlerRoleRef: 'AW_CR',
                statusOnEnterCode: 'aone_172915',
                onSuccess: '{"to":"STEP_2"}', onFail: '{"to":"STOP"}',
              },
            ],
          },
        });
      }),
      http.put('/api/sdlcs/1/steps/10', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析（已更新）', kind: 'analysis',
            instructionMd: '理解需求', checklistJson: null, gatePolicyJson: null,
            required: true, timeoutSeconds: 600, retryBudget: 2,
          },
        });
      }),
    );

    const { container } = renderPage();
    expect(await screen.findByText('流转字段流程')).toBeInTheDocument();

    const editButton = container.querySelector('.ant-table-row .anticon-edit')?.closest('button');
    expect(editButton).toBeTruthy();
    fireEvent.click(editButton!);

    const approvalSwitch = await screen.findByRole('switch', { name: '完成后需要人工批准' });
    expect(approvalSwitch).toHaveAttribute('aria-checked', String(!enabled));
    fireEvent.click(approvalSwitch);
    expect(approvalSwitch).toHaveAttribute('aria-checked', String(enabled));

    const okButton = await screen.findByRole('button', { name: /OK|确\s*定/ });
    fireEvent.click(okButton);

    await waitFor(() => expect(capturedBody).not.toBeNull());

    // Only the form-managed columns may travel; an explicit null/undefined for the
    // deprecated columns would make the server overwrite them instead of keeping them.
    expect(capturedBody!.name).toBe('需求分析');
    expect(capturedBody!.timeoutSeconds).toBe(600);
    expect(capturedBody!.retryBudget).toBe(2);
    expect(JSON.parse(capturedBody!.gatePolicyJson as string)).toEqual(
      enabled ? { evidenceRequired: true, humanApprovalRequired: true } : { evidenceRequired: true },
    );
    expect(capturedBody!).not.toHaveProperty('humanApprovalRequired');
    for (const key of ['handlerRoleRef', 'statusOnEnterCode', 'onSuccess', 'onFail']) {
      expect(capturedBody!).not.toHaveProperty(key);
    }
  });

  it('keeps questions opt-in, removes the legacy question box, and explains where to configure them', async () => {
    const otherPolicy = { evidenceRequired: true, humanApprovalRequired: true };
    let policy = JSON.stringify(otherPolicy);
    const saved: Record<string, unknown>[] = [];
    server.use(
      http.get('/api/sdlcs/1', () => HttpResponse.json({
        success: true, data: { id: '1', name: '提问配置流程', status: 'DRAFT', steps: [{
          id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis',
          instructionMd: '理解需求', gatePolicyJson: policy, required: true,
        }] },
      })),
      http.put('/api/sdlcs/1/steps/10', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        saved.push(body);
        policy = body.gatePolicyJson as string;
        return HttpResponse.json({ success: true, data: { id: '10' } });
      }),
    );
    const { container } = renderPage();
    await screen.findByText('提问配置流程');
    const edit = () => fireEvent.click(container.querySelector('.ant-table-row .anticon-edit')!.closest('button')!);
    edit();
    const label = '该步骤事实不清时询问用户';
    const questionLabel = '需要询问的问题（可选）';
    const toggle = await screen.findByRole('switch', { name: label });
    expect(screen.queryByRole('textbox', { name: questionLabel })).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.queryByRole('textbox', { name: questionLabel })).not.toBeInTheDocument();
    expect(screen.getByLabelText('询问用户功能说明')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /OK|确\s*定/ }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(JSON.parse(policy)).toEqual({ ...otherPolicy, askWhenUnclear: true });
    expect(saved[0]).not.toHaveProperty('askWhenUnclear');
    expect(saved[0]).not.toHaveProperty('askWhenUnclearGuidance');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    edit();
    await screen.findByRole('switch', { name: label });
    expect(screen.queryByRole('textbox', { name: questionLabel })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: label }));
    expect(screen.queryByRole('textbox', { name: questionLabel })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /OK|确\s*定/ }));
    await waitFor(() => expect(saved).toHaveLength(2));
    expect(JSON.parse(policy)).toEqual(otherPolicy);
  });

  it('lets users view checklist and gate policy content via popovers on an ENABLED SDLC', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '可查看配置流程', description: '', status: 'ENABLED',
            workType: null, isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              {
                id: '10', sdlcId: '1', stepOrder: 1, name: '自测交付', kind: 'test', instructionMd: '运行测试并交付',
                checklistJson: '["运行相关测试全部通过","测试日志已保存到 artifacts/output/evidence/",{"id":"optional","text":"实施分支检查","allowNotApplicable":true,"notApplicableWhen":"只澄清未实施"}]',
                gatePolicyJson: '{"evidenceRequired":true,"requiredArtifacts":"evidence/"}',
                required: true, timeoutSeconds: null, retryBudget: null,
              },
            ],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('可查看配置流程')).toBeInTheDocument();

    const tags = screen.getAllByText('已配置');
    expect(tags).toHaveLength(2);

    fireEvent.click(tags[0]);
    expect(await screen.findByText('• 运行相关测试全部通过')).toBeInTheDocument();
    expect(screen.getByText('• 测试日志已保存到 artifacts/output/evidence/')).toBeInTheDocument();
    expect(screen.getByText('允许不适用：只澄清未实施')).toBeInTheDocument();

    fireEvent.click(tags[1]);
    expect(await screen.findByText('evidenceRequired: true')).toBeInTheDocument();
    expect(screen.getByText('requiredArtifacts: evidence/')).toBeInTheDocument();
  });

  it('falls back to raw text in popovers when checklist or policy JSON is invalid', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '非法配置流程', description: '', status: 'DISABLED',
            workType: null, isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              {
                id: '10', sdlcId: '1', stepOrder: 1, name: '异常配置步骤', kind: 'analysis', instructionMd: '查看配置',
                checklistJson: '不是合法JSON',
                gatePolicyJson: '{bad json',
                required: true, timeoutSeconds: null, retryBudget: null,
              },
            ],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('非法配置流程')).toBeInTheDocument();

    const tags = screen.getAllByText('已配置');
    fireEvent.click(tags[0]);
    expect(await screen.findByText('不是合法JSON')).toBeInTheDocument();

    fireEvent.click(tags[1]);
    expect(await screen.findByText('{bad json')).toBeInTheDocument();
  });

  it('shows a help tooltip icon next to the retry budget field in the step edit modal', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '提示流程', description: '', status: 'DRAFT',
            workType: 'BUG', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis', instructionMd: '理解需求', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );

    const { container } = renderPage();
    expect(await screen.findByText('提示流程')).toBeInTheDocument();

    const editButton = container.querySelector('.ant-table-row .anticon-edit')?.closest('button');
    fireEvent.click(editButton!);

    const label = await screen.findByText('建议重试预算');
    const helpSpan = label.closest('span');
    expect(helpSpan).toBeTruthy();
    expect(helpSpan!.querySelector('.anticon-question-circle')).toBeTruthy();
  });

  it('shows help explanations for checklist, policy and retry budget fields in the step modal', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '帮助说明流程', description: '', status: 'DRAFT',
            workType: 'TASK', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('帮助说明流程')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /添加步骤/ }));
    await screen.findAllByText('检查项 JSON');

    const helpIconOf = (labelText: string) => {
      const icon = screen.getAllByText(labelText)
        .map((el) => el.querySelector('.anticon-question-circle'))
        .find(Boolean);
      expect(icon).toBeTruthy();
      return icon!;
    };

    fireEvent.mouseEnter(helpIconOf('检查项 JSON'));
    expect(await screen.findByText(/checklistRequired: true/)).toBeInTheDocument();
    expect(await screen.findByText(/先升级执行器 Runtime/)).toBeInTheDocument();
    expect(await screen.findByText(/未配置的必需项仍须通过/)).toBeInTheDocument();

    fireEvent.mouseEnter(helpIconOf('准入/准出策略 JSON'));
    expect((await screen.findAllByText(/requiredArtifacts/)).length).toBeGreaterThan(0);

    fireEvent.mouseEnter(helpIconOf('建议重试预算'));
    expect(await screen.findByText(/Gate 会校验产出是否满足要求/)).toBeInTheDocument();
  });

  it('shows checklist and policy placeholders consistent with their tooltip examples', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '占位提示流程', description: '', status: 'DRAFT',
            workType: 'TASK', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('占位提示流程')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /添加步骤/ }));
    await screen.findAllByText('检查项 JSON');

    const checklistTextarea = screen.getByPlaceholderText(/"id": "tests-pass"/);
    expect(checklistTextarea).toHaveAttribute('placeholder', '如: [{"id": "tests-pass", "text": "运行相关测试全部通过"}]');

    const policyTextarea = screen.getByPlaceholderText(/"evidenceRequired": true/);
    expect(policyTextarea).toHaveAttribute('placeholder', '如: { "evidenceRequired": true, "requiredArtifacts": ["evidence/"] }');
  });
});

describe('RetryBudgetTooltipContent', () => {
  it('explains meaning, behavior, example, and recommended value', () => {
    render(<RetryBudgetTooltipContent />);
    expect(screen.getByText(/含义：/)).toBeInTheDocument();
    expect(screen.getByText(/Gate 会校验产出是否满足要求/)).toBeInTheDocument();
    expect(screen.getByText(/值为 0（或未填写）：gate 失败后直接终止/)).toBeInTheDocument();
    expect(screen.getByText(/最多有 N 次额外尝试/)).toBeInTheDocument();
    expect(screen.getByText(/示例：/)).toBeInTheDocument();
    expect(screen.getByText(/建议值：/)).toBeInTheDocument();
    expect(screen.getByText(/一般建议设置为 2~3/)).toBeInTheDocument();
  });
});

it('refreshes both open SDLC forms and existing errors without clearing raw drafts or pristine fields', async () => {
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  const id = '334208147726012416';
  const rawJson = '[{"id":"tests-pass","text":"<b>原文</b>"}]';
  let body: unknown;
  server.use(http.get(`/api/sdlcs/${id}`, () => HttpResponse.json({ success: true, data: {
    id, name: '<em>流程</em>', description: '原文说明', status: 'DRAFT', workType: 'TASK', version: 3,
    steps: [{ id: '334208147726012417', sdlcId: id, stepOrder: 1, name: '原步骤', kind: 'analysis', required: true, instructionMd: '执行原文', checklistJson: rawJson, gatePolicyJson: '{bad json', timeoutSeconds: 600, retryBudget: 2 }],
  } })), http.put(`/api/sdlcs/${id}/steps/334208147726012417`, async ({ request }) => { body = await request.json(); return HttpResponse.json({ success: true, data: {} }); }));
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={createMemoryRouter([{ path: '/sdlcs/:id', element: <SdlcDetailPage /> }], { initialEntries: [`/sdlcs/${id}`] })} /></QueryClientProvider>);
  await screen.findByText('<em>流程</em>');
  fireEvent.click(screen.getByRole('button', { name: /编辑信息/ }));
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: '' } });
  await screen.findByText('请输入名称');
  fireEvent.click(screen.getByRole('button', { name: '编辑步骤' }));
  const dialog = screen.getByRole('dialog');
  fireEvent.change(within(dialog).getByLabelText('步骤名称'), { target: { value: '' } });
  fireEvent.change(within(dialog).getByLabelText('建议超时秒数'), { target: { value: '' } });
  fireEvent.change(within(dialog).getByLabelText('建议重试预算'), { target: { value: '' } });
  await screen.findByText('请输入步骤名称');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(await screen.findByText('Enter a step name')).toBeInTheDocument();
  expect(screen.getByText('Enter a name')).toBeInTheDocument();
  expect(screen.queryByText('Enter execution instructions')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Description')).toHaveValue('原文说明');
  expect(within(dialog).getByLabelText('Execution instructions')).toHaveValue('执行原文');
  expect(within(dialog).getByLabelText('Checklist JSON')).toHaveValue(rawJson);
  expect(within(dialog).getByLabelText('Entry/exit policy JSON')).toHaveValue('{bad json');
  expect(screen.getByLabelText('Step 1: 原步骤')).toBeInTheDocument();
  expect(screen.getByText('1 step')).toBeInTheDocument();
  expect(screen.getAllByText('Analysis')).toHaveLength(2);
  fireEvent.change(within(dialog).getByLabelText('Step name'), { target: { value: '<i>draft</i>' } });
  fireEvent.click(within(dialog).getByRole('button', { name: /OK|Confirm/ }));
  expect(await screen.findByText('Enter a valid JSON object')).toBeInTheDocument();
  expect(body).toBeUndefined();
  fireEvent.change(within(dialog).getByLabelText('Entry/exit policy JSON'), { target: { value: '{"requiredArtifacts":["原产物"]}' } });
  fireEvent.click(within(dialog).getByRole('button', { name: /OK|Confirm/ }));
  await waitFor(() => expect(body).toEqual({ name: '<i>draft</i>', kind: 'analysis', required: true, instructionMd: '执行原文', checklistJson: rawJson, gatePolicyJson: '{"requiredArtifacts":["原产物"]}', timeoutSeconds: null, retryBudget: null }));
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});

it('uses the completion locale for a pending SDLC save', async () => {
  await i18n.changeLanguage('zh-CN');
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  let finish!: () => void;
  const pending = new Promise<void>(resolve => { finish = resolve; });
  let started = false;
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  server.use(http.get('/api/sdlcs/1', () => HttpResponse.json({ success: true, data: { id: '1', name: '原名', status: 'DRAFT', steps: [] } })), http.put('/api/sdlcs/1', async () => { started = true; await pending; return HttpResponse.json({ success: true, data: {} }); }));
  renderPage();
  await screen.findByText('原名');
  fireEvent.click(screen.getByRole('button', { name: /编辑信息/ }));
  fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
  await waitFor(() => expect(started).toBe(true));
  await act(async () => { await i18n.changeLanguage('en-US'); });
  finish();
  await waitFor(() => expect(success).toHaveBeenCalledWith('Saved'));
  success.mockRestore();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it.each([
  ['zh-CN', '草稿', '需求', ['分析', '实现', '测试', '审查', '产物', '交接', '清理'], '8 步'],
  ['en-US', 'Draft', 'Requirement', ['Analysis', 'Implementation', 'Test', 'Review', 'Artifact', 'Handoff', 'Cleanup'], '8 steps'],
])('renders every known kind and prototype-like unknown values in %s', async (locale, status, workType, kinds, count) => {
  await i18n.changeLanguage(locale);
  const codes = ['analysis', 'implementation', 'test', 'review', 'artifact', 'handoff', 'cleanup', 'constructor'];
  server.use(http.get('/api/sdlcs/1', () => HttpResponse.json({ success: true, data: { id: '1', name: '原流程', workType: 'REQ', status: 'DRAFT', steps: codes.map((kind, i) => ({ id: String(i), stepOrder: i + 1, name: `raw-${i}`, kind, required: i !== 0, instructionMd: '原执行说明', timeoutSeconds: null, retryBudget: null })) } })));
  renderPage();
  await screen.findByText('原流程');
  expect(screen.getByText(status)).toBeInTheDocument();
  expect(screen.getByText(workType)).toBeInTheDocument();
  for (const kind of kinds) expect(screen.getByText(kind)).toBeInTheDocument();
  expect(screen.getByText('constructor')).toBeInTheDocument();
  expect(screen.getByText(count)).toBeInTheDocument();
  const first = screen.getByLabelText(locale === 'en-US' ? 'Step 1: raw-0' : '步骤 1: raw-0');
  fireEvent.mouseEnter(first);
  expect(await screen.findByText(locale === 'en-US' ? 'Timeout: Not configured' : '超时：未配置')).toBeInTheDocument();
  expect(screen.getByText(locale === 'en-US' ? 'Required: No' : '必需：否')).toBeInTheDocument();
});

it.each([[0, '0 steps'], [1, '1 step'], [3, '3 steps']])('renders a numeric overview plural with %s steps', async (count, expected) => {
  await i18n.changeLanguage('en-US');
  server.use(http.get('/api/sdlcs/1', () => HttpResponse.json({ success: true, data: { id: '1', name: 'Raw', workType: 'constructor', status: 'constructor', steps: Array.from({ length: count as number }, (_, i) => ({ id: String(i), stepOrder: i + 1, name: `step-${i}` })) } })));
  renderPage();
  await screen.findByText('Raw');
  expect(screen.getByText(expected)).toBeInTheDocument();
  expect(screen.getAllByText('constructor')).toHaveLength(2);
});

it('refreshes open checklist help and retry prose while retaining exact JSON examples', async () => {
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  server.use(http.get('/api/sdlcs/1', () => HttpResponse.json({ success: true, data: { id: '1', name: 'Raw', status: 'DRAFT', steps: [] } })));
  renderPage();
  await screen.findByText('Raw');
  fireEvent.click(screen.getByRole('button', { name: '添加步骤' }));
  const dialog = screen.getByRole('dialog');
  fireEvent.keyDown(within(dialog).getByRole('button', { name: '查看检查项 JSON帮助' }), { key: 'Enter' });
  await screen.findByText(/先升级执行器 Runtime/);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(await screen.findByText(/Upgrade the Executor Runtime before setting/)).toBeInTheDocument();
  expect(screen.getByText('[{"id":"code-tests","text":"代码测试通过","allowNotApplicable":true,"notApplicableWhen":"本轮仅澄清需求，未进入代码实施分支"}]')).toBeInTheDocument();
  expect(within(dialog).getByLabelText('Checklist JSON')).toHaveAttribute('placeholder', 'e.g. [{"id": "tests-pass", "text": "运行相关测试全部通过"}]');
  expect(within(dialog).getByLabelText('Entry/exit policy JSON')).toHaveAttribute('placeholder', 'e.g. { "evidenceRequired": true, "requiredArtifacts": ["evidence/"] }');
  expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument();
  fireEvent.keyDown(within(dialog).getByRole('button', { name: 'Help for Suggested retry budget' }), { key: ' ' });
  expect(await screen.findByText(/After a step finishes, the Runtime Gate checks/)).toBeInTheDocument();
});
