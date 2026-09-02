import { beforeEach, describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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

describe('SdlcDetailPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
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
      background: '#fff',
      borderColor: '#ff6a00',
      boxShadow: '0 0 0 2px rgba(255, 106, 0, 0.08), 0 8px 20px rgba(255, 106, 0, 0.08)',
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
                checklistJson: '["运行相关测试全部通过","测试日志已保存到 artifacts/output/evidence/"]',
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
    expect(await screen.findByText('✓ 运行相关测试全部通过')).toBeInTheDocument();
    expect(screen.getByText('✓ 测试日志已保存到 artifacts/output/evidence/')).toBeInTheDocument();

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
