import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemDetailPage } from './WorkitemDetailPage';
import { uploadRequirementDocuments } from './api';
import { useAuthStore } from '@/shared/auth/store';

const mockWorkitem = {
  id: '1', workType: 'REQ', title: '跨境支付重构', contentMd: '# 背景\n重构支付',
  templateId: '1', statusNodeId: '1', statusName: '开发中', sdlcId: '10',
  sdlcName: '标准流程', assigneeType: 'AGENT', assigneeRef: '100',
  assigneeName: 'Coder-01', priority: 1, version: 3,
  gmtCreate: '2026-07-01T10:00:00Z', gmtModified: '2026-07-09T12:00:00Z',
};

const mockTimeline = [
  {
    id: '1', type: 'comment', authorId: '100', authorName: 'Coder-01',
    authorType: 'AGENT', isAgent: true, content: '@Coder-01 已完成代码编写',
    gmtCreate: '2026-07-09T11:00:00Z',
  },
  {
    id: '2', type: 'system', authorId: null, authorName: null,
    authorType: 'SYSTEM', isAgent: false, content: '状态变更: 待开发 → 开发中',
    gmtCreate: '2026-07-08T10:00:00Z',
  },
  {
    id: '3', type: 'system', authorId: null, authorName: null,
    authorType: 'SYSTEM', isAgent: false, content: 'ASSIGN: 100 → 200',
    gmtCreate: '2026-07-08T11:00:00Z',
  },
];

const mockParticipants = [
  { userId: '100', name: 'Coder-01', role: 'DEV', roleName: '开发', isAgent: true, online: true },
  { userId: '200', name: '张三', role: 'REVIEWER', roleName: '评审', isAgent: false, online: false },
];

const mockDeliveryProgress = {
  steps: [
    { stepId: '1', name: '需求分析', status: 'done', executorName: null, subSteps: null },
    { stepId: '2', name: '编码实现', status: 'active', executorName: 'Coder-01', subSteps: [{ name: '编写代码', status: 'done' }, { name: '单元测试', status: 'active' }] },
    { stepId: '3', name: '代码评审', status: 'pending', executorName: null, subSteps: null },
  ],
};

const mockClarification = null;

const mockArtifacts = [
  { id: '1', workitemId: '1', dispatchId: '10', name: 'PaymentService.java', type: 'SOURCE', size: 2048, gmtCreate: '2026-07-09T11:30:00Z' },
];

const mockRequirementDocuments = [
  { id: '21', workitemId: '1', dispatchId: null, name: 'requirements/spec.md', type: 'REQUIREMENT_DOC', size: 128, gmtCreate: '2026-07-09T12:00:00Z' },
];

const mockTemplateDetail = {
  id: 1,
  workType: 'REQ',
  name: '需求默认流程',
  isDefault: true,
  gmtCreate: '2026-07-01T10:00:00Z',
  gmtModified: '2026-07-01T10:00:00Z',
  nodes: [
    { id: 1, templateId: 1, code: 'developing', name: '开发中', category: 'IN_PROGRESS', sort: 2, gmtCreate: '2026-07-01T10:00:00Z' },
    { id: 2, templateId: 1, code: 'verifying', name: '验证中', category: 'IN_PROGRESS', sort: 3, gmtCreate: '2026-07-01T10:00:00Z' },
  ],
  transitions: [
    { id: 100, templateId: 1, fromNodeId: 1, toNodeId: 2, name: '提交验证', gmtCreate: '2026-07-01T10:00:00Z' },
  ],
};

function setupHandlers() {
  return [
    http.get('/api/workitems/1', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockWorkitem,
      });
    }),
    http.get('/api/workitems/1/unified-timeline', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTimeline,
      });
    }),
    http.get('/api/workitems/1/participants', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockParticipants,
      });
    }),
    http.get('/api/workitems/1/mention-candidates', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockParticipants,
      });
    }),
    http.get('/api/workitems/1/delivery-progress', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockDeliveryProgress,
      });
    }),
    http.get('/api/workitems/1/clarification', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockClarification,
      });
    }),
    http.get('/api/workitems/1/artifacts', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockArtifacts,
      });
    }),
    http.get('/api/workitems/1/requirement-documents', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      });
    }),
    http.get('/api/status-templates/1', () => {
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplateDetail,
      });
    }),
  ];
}

function renderPage(id = '1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/workitems/${id}`]}>
        <Routes>
          <Route path="/workitems/:id" element={<WorkitemDetailPage />} />
          <Route path="/workitems" element={<div>工单列表</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemDetailPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders workitem title and status', async () => {
    server.use(...setupHandlers());
    renderPage();

    // Title appears in both breadcrumb and heading — use role to target the heading
    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();
    expect(screen.getByText('开发中')).toBeInTheDocument();
  });

  it('shows the human intervention alert when assigned to a human', async () => {
    server.use(
      http.get('/api/workitems/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          ...mockWorkitem,
          assigneeType: 'HUMAN', assigneeRef: '10000', assigneeName: 'caihe', assigneeDisplayName: '蔡何',
        },
      })),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1'),
    );
    renderPage();

    expect(await screen.findByText('需人工介入：蔡何')).toBeInTheDocument();
    expect(screen.getByText('当前工单已指派给真人，请人工处理、补充决策，或重新指派给数字员工继续交付。')).toBeInTheDocument();
  });

  it('does not show the human intervention alert for agent-assigned workitems', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();
    expect(screen.queryByText(/需人工介入/)).not.toBeInTheDocument();
  });

  it('does not show the human intervention alert when the workitem is finished', async () => {
    server.use(
      http.get('/api/workitems/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          ...mockWorkitem,
          assigneeType: 'HUMAN', assigneeRef: '10000', assigneeName: 'caihe', assigneeDisplayName: '蔡何',
          statusName: '已完成',
        },
      })),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1'),
    );
    renderPage();

    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();
    expect(screen.queryByText(/需人工介入/)).not.toBeInTheDocument();
  });

  it('does not show the human intervention alert when the workitem is canceled', async () => {
    server.use(
      http.get('/api/workitems/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          ...mockWorkitem,
          assigneeType: 'HUMAN', assigneeRef: '10000', assigneeName: 'caihe', assigneeDisplayName: '蔡何',
          statusName: '已取消',
        },
      })),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1'),
    );
    renderPage();

    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();
    expect(screen.queryByText(/需人工介入/)).not.toBeInTheDocument();
  });

  it('does not show the human intervention alert when no human is explicitly assigned', async () => {
    server.use(
      http.get('/api/workitems/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { ...mockWorkitem, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null },
      })),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1'),
    );
    renderPage();

    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();
    expect(screen.queryByText(/需人工介入/)).not.toBeInTheDocument();
  });

  it('renders requirement documents on the workitem detail page', async () => {
    server.use(
      http.get('/api/workitems/1/requirement-documents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockRequirementDocuments,
      })),
      ...setupHandlers(),
    );
    renderPage();

    expect(await screen.findByTestId('requirement-documents-card')).toBeInTheDocument();
    expect(screen.getAllByText('需求/设计文档').length).toBeGreaterThan(0);
    expect(await screen.findByText('spec.md')).toBeInTheDocument();
    expect(screen.getAllByText(/128 B/).length).toBeGreaterThan(0);
  });

  it('posts markdown requirement documents as multipart FormData through the frontend API', async () => {
    let uploadRequested = false;
    let contentType = '';
    let bodyText = '';
    server.use(
      http.post('/api/workitems/1/requirement-documents', async ({ request }) => {
        uploadRequested = true;
        contentType = request.headers.get('content-type') || '';
        bodyText = await request.text();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: '22', workitemId: '1', dispatchId: null, name: 'requirements/plan.md',
            type: 'REQUIREMENT_DOC', size: 6, gmtCreate: '2026-07-09T12:30:00Z',
          }],
        });
      }),
    );

    const result = await uploadRequirementDocuments(1, [new File(['# Plan'], 'plan.md', { type: 'text/markdown' })]);

    expect(uploadRequested).toBe(true);
    expect(contentType.toLowerCase()).toContain('multipart/form-data');
    expect(contentType.toLowerCase()).not.toContain('application/json');
    expect(bodyText).toContain('name="files"');
    expect(bodyText).not.toBe('{"files":{}}');
    expect(result[0].name).toBe('requirements/plan.md');
  });

  it('rejects non-markdown requirement documents before upload', async () => {
    let uploadRequested = false;
    server.use(
      http.post('/api/workitems/1/requirement-documents', () => {
        uploadRequested = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
      ...setupHandlers(),
    );
    renderPage();

    const input = await screen.findByLabelText('选择需求文档');
    fireEvent.change(input, { target: { files: [new File(['plain'], 'notes.txt', { type: 'text/plain' })] } });

    expect(await screen.findByText('仅支持上传 .md 或 .markdown 文档')).toBeInTheDocument();
    expect(uploadRequested).toBe(false);
  });

  it('keeps requirement document commands visible but blocks a read-only member', async () => {
    const inputClick = vi.spyOn(HTMLInputElement.prototype, 'click');
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/workitems/1/requirement-documents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockRequirementDocuments,
      })),
      ...setupHandlers(),
    );
    renderPage();

    const uploadButton = await screen.findByRole('button', { name: /上传/ });
    const deleteButton = await screen.findByRole('button', { name: '删除 spec.md' });
    expect(uploadButton).toBeEnabled();
    expect(deleteButton).toBeEnabled();

    await userEvent.click(uploadButton);
    expect(await screen.findByText('当前为只读权限，上传需求文档需要读写权限')).toBeInTheDocument();
    expect(inputClick).not.toHaveBeenCalled();

    await userEvent.click(deleteButton);
    expect(await screen.findByText('当前为只读权限，删除需求文档需要读写权限')).toBeInTheDocument();
    expect(screen.queryByText('确认删除 spec.md？')).not.toBeInTheDocument();
  });

  it('previews requirement documents', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('# Spec\nDetails', { status: 200 })));
    server.use(
      http.get('/api/workitems/1/requirement-documents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockRequirementDocuments,
      })),
      http.get('/api/artifacts/21/download', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/spec.md',
      })),
      ...setupHandlers(),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '预览 spec.md' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/artifacts/21/preview', expect.anything()));
    expect(await screen.findByRole('heading', { name: 'Spec' })).toBeInTheDocument();
  });

  it('deletes requirement documents after confirmation', async () => {
    let deleteRequested = false;
    server.use(
      http.get('/api/workitems/1/requirement-documents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockRequirementDocuments,
      })),
      http.delete('/api/workitems/1/requirement-documents/21', () => {
        deleteRequested = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: true });
      }),
      ...setupHandlers(),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '删除 spec.md' }));
    expect(await screen.findByText('确认删除 spec.md？')).toBeInTheDocument();
    const confirmTooltip = screen.getByRole('tooltip', { name: /确认删除 spec\.md/ });
    await userEvent.click(within(confirmTooltip).getByRole('button', { name: /删\s*除/ }));

    await waitFor(() => expect(deleteRequested).toBe(true));
    expect(await screen.findByText('需求文档已删除')).toBeInTheDocument();
  });

  it('shows squad members in right panel', async () => {
    server.use(...setupHandlers());
    renderPage();

    await waitFor(() => expect(screen.getAllByText('成员').length).toBeGreaterThan(0));
    await userEvent.click(screen.getByRole('tab', { name: '真人参与者' }));
    expect(await screen.findByText('张三')).toBeInTheDocument();
  });

  it('shows delivery progress steps', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByText('交付进度跟踪')).toBeInTheDocument();
    expect(screen.getByText('需求分析')).toBeInTheDocument();
    expect(screen.getByText('编码实现')).toBeInTheDocument();
    expect(screen.getByText('代码评审')).toBeInTheDocument();
  });

  it('renders timeline comments', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByText('评论 & 时间线')).toBeInTheDocument();
    expect(await screen.findByText(/已完成代码编写/)).toBeInTheDocument();
  });

  it('opens a markdown artifact preview from a timeline comment path', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('# Completion Report\nPASS', { status: 200 })));
    server.use(
      http.get('/api/workitems/1/unified-timeline', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 10,
          type: 'comment',
          authorId: 40015,
          authorName: 'AW测试工程师',
          authorType: 'AGENT',
          isAgent: true,
          content: '证据：artifacts/output/deliverables/step-400176-completion-report.md',
          gmtCreate: new Date().toISOString(),
        }],
      })),
      http.get('/api/workitems/1/artifacts', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 7,
          workitemId: 1,
          dispatchId: 400176,
          name: 'deliverables/step-400176-completion-report.md',
          type: 'DELIVERABLE',
          size: 100,
          gmtCreate: '2026-07-28T10:00:00Z',
        }],
      })),
      http.get('/api/artifacts/7/download', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/report.md',
      })),
      ...setupHandlers(),
    );

    renderPage();

    await screen.findByRole('button', { name: '打开产物 artifacts/output/deliverables/step-400176-completion-report.md' });
    fireEvent.click(screen.getByTestId('artifact-inline-link'));

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/artifacts/7/preview');
    expect(await screen.findByRole('heading', { name: 'Completion Report' })).toBeInTheDocument();
    expect(screen.getByText('PASS')).toBeInTheDocument();
  });

  it('highlights employee mentions in timeline comments', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByText('@Coder-01')).toHaveStyle({
      color: '#0958d9',
      background: '#e6f4ff',
      fontWeight: '600',
    });
  });

  it('shows one live thinking status below an mentioned worker comment', async () => {
    server.use(
      http.get('/api/workitems/1/unified-timeline', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 10, type: 'comment', authorId: 1, authorName: 'zichaojin',
          authorType: 'HUMAN', isAgent: false, content: '@开发环境DBA运维 host 是什么？',
          gmtCreate: new Date().toISOString(),
          interactions: [{ guidanceId: 99, targetAgentId: 40030,
            targetAgentName: '开发环境DBA运维', status: 'DELIVERED' }],
        }],
      })),
      ...setupHandlers(),
    );

    renderPage();

    expect(await screen.findByText('开发环境DBA运维 正在思考…')).toBeInTheDocument();
    expect(screen.getAllByTestId('comment-interaction-status')).toHaveLength(1);
  });

  it('renders a side conversation answer directly below the question it answers', async () => {
    server.use(
      http.get('/api/workitems/1/unified-timeline', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 10, type: 'comment', authorId: 10004, authorName: 'zichaojin',
          authorType: 'HUMAN', isAgent: false,
          content: '@AW全栈开发 为啥刚才我@你但是你给到了评审呢',
          gmtCreate: new Date().toISOString(),
          interactions: [{
            guidanceId: 99, targetAgentId: 40013, targetAgentName: 'AW全栈开发', status: 'APPLIED',
            replyCommentId: 11, replyContent: '因为当时正式流程已经交接给了评审。',
            repliedAt: new Date().toISOString(),
          }],
        }],
      })),
      ...setupHandlers(),
    );

    renderPage();

    expect(await screen.findByText('AW全栈开发 回复了这个问题')).toBeInTheDocument();
    expect(screen.getByText('因为当时正式流程已经交接给了评审。')).toBeInTheDocument();
    expect(screen.getByTestId('comment-interaction-reply')).toBeInTheDocument();
    expect(screen.queryByText('AW全栈开发 正在思考…')).not.toBeInTheDocument();
  });

  it('opens an artifact preview from an interaction reply path', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('# Reply Report\nPASS', { status: 200 })));
    server.use(
      http.get('/api/workitems/1/unified-timeline', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 10, type: 'comment', authorId: 10004, authorName: 'zichaojin',
          authorType: 'HUMAN', isAgent: false,
          content: '@AW全栈开发 看一下报告',
          gmtCreate: new Date().toISOString(),
          interactions: [{
            guidanceId: 99, targetAgentId: 40013, targetAgentName: 'AW全栈开发', status: 'APPLIED',
            replyCommentId: 11, replyContent: '证据：artifacts/output/deliverables/reply-report.md',
            repliedAt: new Date().toISOString(),
          }],
        }],
      })),
      http.get('/api/workitems/1/artifacts', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 8,
          workitemId: 1,
          dispatchId: 400177,
          name: 'deliverables/reply-report.md',
          type: 'DELIVERABLE',
          size: 100,
          gmtCreate: '2026-07-28T10:00:00Z',
        }],
      })),
      http.get('/api/artifacts/8/download', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/reply-report.md',
      })),
      ...setupHandlers(),
    );

    renderPage();

    const reply = await screen.findByTestId('comment-interaction-reply');
    fireEvent.click(within(reply).getByRole('button', { name: '打开产物 artifacts/output/deliverables/reply-report.md' }));

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/artifacts/8/preview');
    expect(await screen.findByRole('heading', { name: 'Reply Report' })).toBeInTheDocument();
  });

  it('renders assignment events with participant names and ids', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByText('ASSIGN: Coder-01（100） -> 张三（200）')).toBeInTheDocument();
    expect(screen.queryByText('ASSIGN: 100 → 200')).not.toBeInTheDocument();
  });

  it('keeps workitem content, timeline, and right panel visually bounded', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByTestId('workitem-content-section')).toHaveStyle('border: 1px solid #e5e7eb');
    expect(screen.getByTestId('workitem-timeline-section')).toHaveStyle('border: 1px solid #e5e7eb');
    expect(screen.getByTestId('workitem-right-panel')).toHaveStyle('width: clamp(340px, 28vw, 420px)');
  });

  it('keeps the header, subtitle metadata, timeline, and comment composer comfortably spaced', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect((await screen.findByText('优先级: P1')).parentElement).toHaveStyle('margin-top: 14px');
    expect(screen.getByTestId('workitem-comment-input')).toHaveStyle('margin-top: 14px');
  });

  it('uses a multiline autosizing composer for comments', async () => {
    server.use(...setupHandlers());
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    expect(composer.tagName).toBe('TEXTAREA');
    await user.click(composer);
    expect(composer).toHaveAttribute('rows', '3');
    expect(composer).toHaveStyle({
      lineHeight: '22px',
      letterSpacing: '0',
      whiteSpace: 'pre-wrap',
      overflowWrap: 'break-word',
    });
  });

  it('keeps mention choices highlighted without mirroring composer text', async () => {
    server.use(...setupHandlers());
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@');
    const option = await screen.findByRole('menuitem', { name: /Coder-01/ });

    expect(within(option).getByText('@Coder-01')).toHaveStyle({
      color: '#0958d9',
      background: '#e6f4ff',
      padding: '0px 4px',
      fontWeight: '600',
    });
    await user.click(option);
    expect(composer).toHaveValue('@Coder-01 ');
    expect(screen.queryByTestId('comment-input-highlight-layer')).not.toBeInTheDocument();
  });

  it('inserts an agent mention at the active multiline caret without a highlight mirror', async () => {
    server.use(...setupHandlers());
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...') as HTMLTextAreaElement;
    fireEvent.change(composer, {
      target: {
        value: '第一行\n@Co第二行',
      },
    });
    composer.setSelectionRange(7, 7);
    fireEvent.keyUp(composer);

    await user.click(await screen.findByRole('menuitem', { name: /Coder-01/ }));

    expect(composer).toHaveValue('第一行\n@Coder-01 第二行');
    expect(composer.selectionStart).toBe('@Coder-01 '.length + '第一行\n'.length);
    expect(screen.queryByTestId('comment-input-highlight-layer')).not.toBeInTheDocument();
    expect(screen.queryByTestId('comment-input-highlight-content')).not.toBeInTheDocument();
  });

  it('uses the native textarea as the only visible comment composer text layer', async () => {
    server.use(...setupHandlers());
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '中文 abc');

    expect(composer).toHaveValue('中文 abc');
    expect(screen.queryByTestId('comment-input-highlight-layer')).not.toBeInTheDocument();
    expect(screen.queryByTestId('comment-input-highlight-content')).not.toBeInTheDocument();
    expect(composer).not.toHaveStyle({ color: 'transparent' });
  });

  it('keeps long comment input native without a synchronized mirror layer', async () => {
    server.use(...setupHandlers());
    renderPage();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    const longComment = [
      '@Coder-01 请检查长评论场景',
      'line 2',
      'line 3',
      'line 4',
      'line 5',
      'line 6',
      'line 7',
      'line 8',
      'line 9',
      'line 10',
      'line 11',
      'line 12',
    ].join('\n');

    fireEvent.change(composer, {
      target: {
        value: longComment,
      },
    });
    fireEvent.scroll(composer, { target: { scrollTop: 88 } });

    expect(composer).toHaveValue(longComment);
    expect(screen.queryByTestId('comment-input-highlight-layer')).not.toBeInTheDocument();
    expect(screen.queryByTestId('comment-input-highlight-content')).not.toBeInTheDocument();
  });

  it('sends selected agent mentions as durable worker guidance', async () => {
    let requestBody: unknown;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 10, workitemId: 1, contentMd: '先检查并发边界' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@');
    await user.click(await screen.findByRole('menuitem', { name: /Coder-01/ }));
    await user.type(composer, '先检查并发边界');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '@Coder-01 先检查并发边界', targetAgentIds: [100],
    }));
  });

  it('sends selected human mentions without worker guidance targets', async () => {
    let requestBody: unknown;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 15, workitemId: 1, contentMd: '@张三 请确认验收口径' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@张');
    await user.click(await screen.findByRole('menuitem', { name: /张三/ }));
    await user.type(composer, '请确认验收口径');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '@张三 请确认验收口径', targetAgentIds: [], targetHumanIds: [200],
    }));
  });

  it('selects a non-first mention candidate with the keyboard before sending guidance', async () => {
    let requestBody: unknown;
    server.use(
      http.get('/api/workitems/1/participants', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { userId: '100', name: 'Coder-01', role: 'DEV', roleName: '开发', isAgent: true, online: true },
          { userId: '101', name: 'Coder-02', role: 'DEV', roleName: '开发', isAgent: true, online: true },
          { userId: '200', name: '张三', role: 'REVIEWER', roleName: '评审', isAgent: false, online: false },
        ],
      })),
      http.get('/api/workitems/1/mention-candidates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { userId: '100', name: 'Coder-01', role: 'DEV', roleName: '开发', isAgent: true, online: true },
          { userId: '101', name: 'Coder-02', role: 'DEV', roleName: '开发', isAgent: true, online: true },
        ],
      })),
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 13, workitemId: 1, contentMd: '@Coder-02 请看这里' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@Coder');
    expect(await screen.findAllByRole('menuitem')).toHaveLength(2);

    await user.keyboard('{ArrowDown}{Enter}');
    await waitFor(() => expect(composer).toHaveValue('@Coder-02 '));
    await user.type(composer, '请看这里');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '@Coder-02 请看这里', targetAgentIds: [101],
    }));
  });

  it('can mention an agent outside current workitem participants', async () => {
    let requestBody: unknown;
    server.use(
      http.get('/api/workitems/1/mention-candidates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          ...mockParticipants,
          {
            userId: '40037',
            name: 'AW代码冲突解决工程师',
            role: 'AGENT',
            roleName: '数字员工',
            isAgent: true,
            online: true,
          },
        ],
      })),
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 14, workitemId: 1, contentMd: '@AW代码冲突解决工程师 看下冲突' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@冲突');
    await user.click(await screen.findByRole('menuitem', { name: /AW代码冲突解决工程师/ }));
    await user.type(composer, '看下冲突');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '@AW代码冲突解决工程师 看下冲突', targetAgentIds: [40037],
    }));
  });

  it('sends a comment when pressing Enter in the composer', async () => {
    let requestBody: unknown;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 11, workitemId: 1, contentMd: '键盘发送评论' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '键盘发送评论');
    await user.keyboard('{Enter}');

    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '键盘发送评论', targetAgentIds: [],
    }));
  });

  it('keeps Shift Enter as a newline in the comment composer', async () => {
    let requestBody: unknown;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 12, workitemId: 1, contentMd: '第一行\n第二行' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '第一行');
    await user.keyboard('{Shift>}{Enter}{/Shift}');
    await user.type(composer, '第二行');

    expect(requestBody).toBeUndefined();
    expect(composer).toHaveValue('第一行\n第二行');

    await user.click(screen.getByRole('button', { name: /发送/ }));
    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '第一行\n第二行', targetAgentIds: [],
    }));
  });

  it('keeps the comment send button visually aligned with the multiline composer', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByTestId('workitem-comment-input')).toHaveStyle({
      alignItems: 'stretch',
    });
    const sendButton = await screen.findByRole('button', { name: /发送/ });
    expect(sendButton).toHaveStyle({
      alignSelf: 'stretch',
      height: 'auto',
      background: '#fff7ed',
      color: '#c2410c',
    });
    expect(sendButton).not.toHaveStyle({
      minHeight: '86px',
    });
  });

  it('aligns the action row with the requirement description card', async () => {
    server.use(...setupHandlers());
    renderPage();

    expect(await screen.findByTestId('workitem-action-bar')).toHaveStyle({
      padding: '12px 0',
    });
  });

  it('edits and saves the workitem title and markdown content', async () => {
    let requestedBody: Record<string, unknown> | null = null;
    server.use(
      ...setupHandlers(),
      http.put('/api/workitems/1/content', async ({ request }) => {
        requestedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            ...mockWorkitem,
            title: '跨境支付重构更新',
            contentMd: '# 背景\n重构支付并补充风控',
            version: 4,
          },
        });
      }),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /编辑/ }));
    await userEvent.clear(screen.getByLabelText('工单标题'));
    await userEvent.type(screen.getByLabelText('工单标题'), '跨境支付重构更新');
    await userEvent.clear(screen.getByLabelText('工单正文'));
    await userEvent.type(screen.getByLabelText('工单正文'), '# 背景\n重构支付并补充风控');
    await userEvent.click(screen.getByRole('button', { name: /保存/ }));

    await waitFor(() => {
      expect(requestedBody).toEqual({
        title: '跨境支付重构更新',
        contentMd: '# 背景\n重构支付并补充风控',
      });
    });
    expect(await screen.findByRole('heading', { name: '跨境支付重构更新' })).toBeInTheDocument();
    expect(await screen.findByText('重构支付并补充风控')).toBeInTheDocument();
  });

  it('transitions status through available template transition', async () => {
    let requestedBody: Record<string, unknown> | null = null;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/transition', async ({ request }) => {
        requestedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { ...mockWorkitem, statusNodeId: '2', statusName: '验证中', version: 4 },
        });
      }),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /流转状态/ }));
    await userEvent.click(await screen.findByRole('button', { name: /提交验证/ }));

    expect(requestedBody).toEqual({ toNodeId: 2 });
  });

  it('syncs the current workitem from Aone on demand', async () => {
    let syncRequested = false;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/external-sync', () => {
        syncRequested = true;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { imported: 0, updated: 1, commentsImported: 2, workitemIds: [1] },
        });
      }),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /同步 Aone/ }));

    expect(syncRequested).toBe(true);
    expect(await screen.findByText('同步完成：新增 0，更新 1，评论 2')).toBeInTheDocument();
  });

  it('deletes the current native workitem after confirmation', async () => {
    let deleteRequested = false;
    server.use(
      ...setupHandlers(),
      http.delete('/api/workitems/1', () => {
        deleteRequested = true;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: null,
        });
      }),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /删除工单/ }));
    const dialog = await screen.findByRole('dialog', { name: '删除工单' });
    await userEvent.click(within(dialog).getByRole('button', { name: /删\s*除/ }));

    await waitFor(() => expect(deleteRequested).toBe(true));
    expect(await screen.findByText('工单列表')).toBeInTheDocument();
  });

  it('shows backend reason when deleting a running workitem is rejected', async () => {
    server.use(
      ...setupHandlers(),
      http.delete('/api/workitems/1', () => {
        return HttpResponse.json({
          success: false, code: '13007', message: '工单正在执行中，请等待完成或结束后再删除', traceId: null,
          data: null,
        }, { status: 409 });
      }),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /删除工单/ }));
    const dialog = await screen.findByRole('dialog', { name: '删除工单' });
    await userEvent.click(within(dialog).getByRole('button', { name: /删\s*除/ }));

    expect(await screen.findByText('工单正在执行中，请等待完成或结束后再删除')).toBeInTheDocument();
  });

  it('disables delete action when workitem is not deletable', async () => {
    const [, ...handlers] = setupHandlers();
    server.use(
      ...handlers,
      http.get('/api/workitems/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            ...mockWorkitem,
            deletable: false,
            deletableReason: '工单正在执行中，请等待完成或结束后再删除',
          },
        });
      }),
    );

    renderPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /删除工单/ })).toBeDisabled();
    });
  });

  it('highlights a newly mentioned human once participants refresh after sending', async () => {
    let participantsCallCount = 0;
    const humanOnlyInCandidates = {
      userId: '300', name: '李四', role: 'QA', roleName: '测试', isAgent: false, online: false,
    };
    server.use(
      http.get('/api/workitems/1/participants', () => {
        participantsCallCount += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: participantsCallCount === 1 ? mockParticipants : [...mockParticipants, humanOnlyInCandidates],
        });
      }),
      http.get('/api/workitems/1/mention-candidates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [...mockParticipants, humanOnlyInCandidates],
      })),
      http.get('/api/workitems/1/unified-timeline', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: participantsCallCount >= 2 ? [{
          id: 20, type: 'comment', authorId: 1, authorName: 'zichaojin',
          authorType: 'HUMAN', isAgent: false, content: '@李四 请确认排期',
          gmtCreate: new Date().toISOString(),
        }] : mockTimeline,
      })),
      http.post('/api/workitems/1/comments', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 20, workitemId: 1, contentMd: '@李四 请确认排期' },
      })),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1/participants'
        && h.info.path !== '/api/workitems/1/mention-candidates'
        && h.info.path !== '/api/workitems/1/unified-timeline'
        && h.info.path !== '/api/workitems/1/comments'),
    );

    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@李');
    await user.click(await screen.findByRole('menuitem', { name: /李四/ }));
    await user.type(composer, '请确认排期');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(participantsCallCount).toBeGreaterThanOrEqual(2));
    expect(await screen.findByText('@李四')).toHaveStyle({
      color: '#0958d9',
      background: '#e6f4ff',
      fontWeight: '600',
    });
  });

  it('searches remote mention candidates beyond the default limit as the user types', async () => {
    const remoteOnlyCandidate = {
      userId: '500', name: '王五工程师', role: 'DEV', roleName: '开发', isAgent: false, online: false,
    };
    let lastQuery: string | null = null;
    server.use(
      http.get('/api/workitems/1/mention-candidates', ({ request }) => {
        const url = new URL(request.url);
        lastQuery = url.searchParams.get('q');
        const data = lastQuery === '王五' ? [...mockParticipants, remoteOnlyCandidate] : mockParticipants;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data,
        });
      }),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1/mention-candidates'),
    );

    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@王五');

    await waitFor(() => expect(lastQuery).toBe('王五'));
    expect(await screen.findByRole('menuitem', { name: /王五工程师/ })).toBeInTheDocument();
  });

  it('keeps a query-only selected mention target after the query clears and more text is typed', async () => {
    const remoteOnlyCandidate = {
      userId: '500', name: '王五工程师', role: 'DEV', roleName: '开发', isAgent: false, online: false,
    };
    let requestBody: unknown;
    server.use(
      http.get('/api/workitems/1/mention-candidates', ({ request }) => {
        const url = new URL(request.url);
        const q = url.searchParams.get('q');
        const data = q === '王五' ? [...mockParticipants, remoteOnlyCandidate] : mockParticipants;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data,
        });
      }),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 21, workitemId: 1, contentMd: '@王五工程师 请确认排期' },
        });
      }),
      ...setupHandlers().filter((h) => h.info.path !== '/api/workitems/1/mention-candidates'
        && h.info.path !== '/api/workitems/1/comments'),
    );

    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '@王五');
    await user.click(await screen.findByRole('menuitem', { name: /王五工程师/ }));
    expect(composer).toHaveValue('@王五工程师 ');

    // Selecting clears the query, so mentionCandidates falls back to the default
    // (王五工程师-free) result set — the target id must still survive further typing.
    await user.type(composer, '请确认排期');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(requestBody).toEqual({
      contentMd: '@王五工程师 请确认排期', targetAgentIds: [], targetHumanIds: [500],
    }));
  });

  it('shows the AI clarification entry and existing clarification material when enabled', async () => {
    server.use(
      http.get('/api/workitems/1/clarification', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { workitemId: '1', contentMd: '# 澄清结论\n补充风控口径', gmtCreate: '2026-07-09T12:00:00Z' },
      })),
      ...setupHandlers(),
    );
    renderPage();

    expect(await screen.findByText('交付进度跟踪')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /AI 需求澄清/ })).toBeInTheDocument();
    expect(screen.getByText('澄清材料 (AI 生成)')).toBeInTheDocument();
    expect(screen.getByText('补充风控口径')).toBeInTheDocument();
  });

  it('renders CommentInput outside the scrollable area in a sticky bottom container', async () => {
    server.use(...setupHandlers());
    renderPage();

    const stickyBar = await screen.findByTestId('workitem-sticky-comment');
    expect(stickyBar).toHaveStyle({ flexShrink: '0' });

    const commentInput = screen.getByTestId('workitem-comment-input');
    expect(stickyBar.contains(commentInput)).toBe(true);

    const scrollArea = screen.getByTestId('workitem-left-scroll');
    expect(scrollArea.contains(commentInput)).toBe(false);
    expect(scrollArea).toHaveStyle({ overflowY: 'auto' });
  });

  it('uses parent-relative height on root container to prevent viewport clipping', async () => {
    server.use(...setupHandlers());
    renderPage();

    const stickyBar = await screen.findByTestId('workitem-sticky-comment');
    const rootContainer = stickyBar.parentElement!.parentElement!;
    expect(rootContainer).toHaveStyle({ height: '100%' });

    const scrollArea = screen.getByTestId('workitem-left-scroll');
    expect(scrollArea).toHaveStyle({ minHeight: '0' });
  });

  it('clears the composer after a successful comment submission', async () => {
    let requestBody: unknown;
    server.use(
      ...setupHandlers(),
      http.post('/api/workitems/1/comments', async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 30, workitemId: 1, contentMd: '测试发送清空' },
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await user.type(composer, '测试发送清空');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(requestBody).toEqual({ contentMd: '测试发送清空', targetAgentIds: [] }));
    expect(composer).toHaveValue('');
  });

  it('collapses the comment composer when empty and unfocused, expands on focus', async () => {
    server.use(...setupHandlers());
    renderPage();
    const user = userEvent.setup();

    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    expect(composer).toHaveAttribute('rows', '1');

    await user.click(composer);
    expect(composer).toHaveAttribute('rows', '3');
  });
});
