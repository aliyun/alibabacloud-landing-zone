import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { MemoryListPage, normalizeMemoryOwnerRef } from './MemoryListPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

function renderPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MemoryListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MemoryListPage', () => {
  it('accepts only positive integer worker IDs for the owner filter', () => {
    expect(normalizeMemoryOwnerRef(400130)).toBe(400130);
    expect(normalizeMemoryOwnerRef(1.5)).toBeUndefined();
    expect(normalizeMemoryOwnerRef(-1)).toBeUndefined();
    expect(normalizeMemoryOwnerRef(null)).toBeUndefined();
  });

  it('offers employee squad and workspace scopes without repository scope', async () => {
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新增记忆/ }));
    await userEvent.click(screen.getByRole('combobox', { name: '范围' }));

    expect((await screen.findAllByText('员工')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('小队').length).toBeGreaterThan(0);
    expect(screen.getAllByText('工作空间全局').length).toBeGreaterThan(0);
    expect(screen.queryByText('仓库')).not.toBeInTheDocument();
  });

  it('shows the owning digital worker and dispatch provenance for MCP memories', async () => {
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 3,
          scope: 'AGENT',
          ownerRef: 400130,
          type: 'BEST_PRACTICE',
          status: 'PENDING',
          source: 'MCP',
          sourceRef: '{"dispatchId":456,"workitemId":123,"agentId":400130}',
          title: '评论回复规则',
          contentMd: '交互回复应挂在提问评论下方。',
          gmtCreate: '2026-08-03',
        }],
      })),
      http.get('/api/agents/400130', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 400130, name: 'AW全栈开发' },
      })),
    );

    renderPage();

    expect(await screen.findByText('AW全栈开发 (400130)')).toBeInTheDocument();
    expect(screen.getByText('工单 123 · 执行 456')).toBeInTheDocument();
  });

  it('renders memory cards with CRUD and pending review actions', async () => {
    server.use(
      http.get('/api/memories', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            {
              id: 1, scope: 'ORG', type: 'FACT', status: 'ADOPTED',
              title: '技术栈选型', contentMd: '项目使用 Java 17',
              sourceRef: null, gmtCreate: '2026-07-01',
            },
            {
              id: 2, scope: 'REPO', type: 'RULE', status: 'PENDING',
              title: '评审规范', contentMd: '提交前需要完成自测',
              sourceRef: null, gmtCreate: '2026-07-02',
            },
          ],
        });
      }),
    );
    renderPage();

    expect(await screen.findByText('技术栈选型')).toBeInTheDocument();
    expect(screen.getByText('项目使用 Java 17')).toBeInTheDocument();
    expect(screen.getByText('评审规范')).toBeInTheDocument();
    expect(screen.getByText('提交前需要完成自测')).toBeInTheDocument();
    expect(screen.getByText('已采纳')).toBeInTheDocument();
    expect(screen.getByText('待审核')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新增/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /审核台/ })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /编辑/ })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: /删除/ })).toHaveLength(2);
    expect(screen.getByRole('button', { name: /^审核$/ })).toBeInTheDocument();
  });

  it('renders card titles inside a tooltip for long-text ellipsis', async () => {
    const longTitle = '这是一段非常长的记忆标题内容用于验证文字省略和悬浮提示功能是否正常工作';
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 10, scope: 'ORG', type: 'FACT', status: 'ADOPTED',
            title: longTitle, contentMd: '短内容',
            sourceRef: null, gmtCreate: '2026-08-01',
          },
          {
            id: 11, scope: 'ORG', type: 'RULE', status: 'ADOPTED',
            title: '另一条记忆', contentMd: '内容',
            sourceRef: null, gmtCreate: '2026-08-02',
          },
        ],
      })),
    );
    renderPage();

    expect(await screen.findByText(longTitle)).toBeInTheDocument();
    const cards = screen.getAllByRole('button', { name: /编辑/ });
    expect(cards).toHaveLength(2);
  });

  it('keeps mutation entries visible but blocks them for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 1,
          scope: 'ORG',
          type: 'FACT',
          status: 'ADOPTED',
          title: '技术栈选型',
          contentMd: '项目使用 Java 17',
          sourceRef: null,
          gmtCreate: '2026-07-01',
        }],
      })),
    );

    renderPage('READ_ONLY');
    const createButton = await screen.findByRole('button', { name: /新增记忆/ });
    const importButton = screen.getByRole('button', { name: /AI 导入/ });

    await userEvent.click(createButton);
    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，新增记忆需要读写权限');
    expect(screen.queryByRole('dialog', { name: /新增记忆/ })).not.toBeInTheDocument();

    await userEvent.click(importButton);
    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，AI 导入记忆需要读写权限');

    await userEvent.click(screen.getByRole('button', { name: /删除/ }));
    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，删除记忆需要读写权限');
    expect(screen.queryByText('确认删除此记忆？')).not.toBeInTheDocument();
    errorSpy.mockRestore();
  });

  it('enters inline review mode on the clicked card without navigating', async () => {
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 1, scope: 'ORG', type: 'FACT', status: 'PENDING',
            title: '待审核记忆', contentMd: '完整审核内容展示',
            sourceRef: null, gmtCreate: '2026-07-01',
          },
          {
            id: 2, scope: 'ORG', type: 'RULE', status: 'ADOPTED',
            title: '已采纳记忆', contentMd: '普通卡片',
            sourceRef: null, gmtCreate: '2026-07-02',
          },
        ],
      })),
    );
    renderPage();
    await screen.findByText('待审核记忆');

    expect(screen.getByRole('button', { name: /^审核$/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /check 采纳/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^审核$/ }));

    expect(await screen.findByRole('button', { name: /check 采纳/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /编辑采纳/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /驳回/ })).toBeInTheDocument();
    expect(screen.getByText('完整审核内容展示')).toBeInTheDocument();
  });

  it('switches review mode to a new card when clicking another review button', async () => {
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 1, scope: 'ORG', type: 'FACT', status: 'PENDING',
            title: '记忆A', contentMd: '内容A',
            sourceRef: null, gmtCreate: '2026-07-01',
          },
          {
            id: 2, scope: 'ORG', type: 'RULE', status: 'PENDING',
            title: '记忆B', contentMd: '内容B',
            sourceRef: null, gmtCreate: '2026-07-02',
          },
        ],
      })),
    );
    renderPage();
    await screen.findByText('记忆A');

    const reviewButtons = screen.getAllByRole('button', { name: /^审核$/ });
    expect(reviewButtons).toHaveLength(2);
    await userEvent.click(reviewButtons[0]);

    expect(await screen.findByRole('button', { name: /check 采纳/ })).toBeInTheDocument();

    const remainingReviewBtn = screen.getByRole('button', { name: /^审核$/ });
    await userEvent.click(remainingReviewBtn);

    const adoptButtons = screen.getAllByRole('button', { name: /check 采纳/ });
    expect(adoptButtons).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: /^审核$/ })).toHaveLength(1);
  });

  it('updates card status and hides review button after successful approve', async () => {
    let listRequestCount = 0;
    server.use(
      http.get('/api/memories', () => {
        listRequestCount++;
        const status = listRequestCount > 1 ? 'ADOPTED' : 'PENDING';
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1, scope: 'ORG', type: 'FACT', status,
            title: '审核目标', contentMd: '内容',
            sourceRef: null, gmtCreate: '2026-07-01',
          }],
        });
      }),
      http.post('/api/memories/1/review', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: null,
      })),
    );
    renderPage();
    await screen.findByText('审核目标');

    await userEvent.click(screen.getByRole('button', { name: /^审核$/ }));
    await screen.findByRole('button', { name: /check 采纳/ });

    await userEvent.click(screen.getByRole('button', { name: /check 采纳/ }));

    await vi.waitFor(() => {
      expect(screen.queryByRole('button', { name: /^审核$/ })).not.toBeInTheDocument();
    });
    expect(await screen.findByText('已采纳')).toBeInTheDocument();
  });

  it('renders review mode content in a scrollable container', async () => {
    const longContent = '这是一段很长的记忆内容用于验证滚动区域。'.repeat(50);
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 1, scope: 'ORG', type: 'FACT', status: 'PENDING',
          title: '长文记忆', contentMd: longContent,
          sourceRef: null, gmtCreate: '2026-07-01',
        }],
      })),
    );
    renderPage();
    await screen.findByText('长文记忆');

    await userEvent.click(screen.getByRole('button', { name: /^审核$/ }));

    const scrollContainer = await screen.findByText(longContent);
    expect(scrollContainer).toBeInTheDocument();
    expect(scrollContainer.style.overflowY).toBe('auto');
  });

  const groupedFixture = [
    {
      scope: 'AGENT', ownerRef: 400130, ownerName: 'AW全栈开发', total: 2,
      memories: [
        {
          id: 3, scope: 'AGENT', ownerRef: 400130, type: 'FACT', status: 'ADOPTED',
          title: '分组记忆一', contentMd: '内容一',
          sourceRef: null, gmtCreate: '2026-08-02',
        },
        {
          id: 2, scope: 'AGENT', ownerRef: 400130, type: 'RULE', status: 'ADOPTED',
          title: '分组记忆二', contentMd: '内容二',
          sourceRef: null, gmtCreate: '2026-08-01',
        },
      ],
    },
    {
      scope: 'AGENT', ownerRef: 999999, ownerName: null, total: 1,
      memories: [
        {
          id: 1, scope: 'AGENT', ownerRef: 999999, type: 'FACT', status: 'PENDING',
          title: '孤儿记忆', contentMd: '内容三',
          sourceRef: null, gmtCreate: '2026-08-01',
        },
      ],
    },
    {
      scope: 'ORG', ownerRef: null, ownerName: null, total: 1,
      memories: [
        {
          id: 4, scope: 'ORG', ownerRef: null, type: 'FACT', status: 'ADOPTED',
          title: '组织记忆', contentMd: '内容四',
          sourceRef: null, gmtCreate: '2026-07-30',
        },
      ],
    },
  ];

  const agentHandlers = [
    http.get('/api/agents/400130', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { id: 400130, name: 'AW全栈开发' },
    })),
    http.get('/api/agents/999999', () => HttpResponse.json({
      success: false, code: 'AGENT_NOT_FOUND', message: 'not found', traceId: null, data: null,
    })),
  ];

  it('groups memories by digital worker in the by-agent view', async () => {
    let groupRequests = 0;
    server.use(
      ...agentHandlers,
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/memories/grouped', () => {
        groupRequests += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: groupedFixture,
        });
      }),
    );
    renderPage();

    expect(groupRequests).toBe(0);
    fireEvent.click(screen.getByRole('radio', { name: '按员工' }));

    expect(await screen.findByText('AW全栈开发')).toBeInTheDocument();
    expect(screen.getByText('2 条记忆')).toBeInTheDocument();
    expect(screen.getAllByText('1 条记忆')).toHaveLength(2);
    expect(screen.getByText(/未归属/)).toBeInTheDocument();
    expect(screen.getByText('组织级')).toBeInTheDocument();
    expect(screen.getByText('分组记忆一')).toBeInTheDocument();
    expect(screen.getByText('分组记忆二')).toBeInTheDocument();
    expect(screen.getByText('孤儿记忆')).toBeInTheDocument();
    expect(screen.getByText('组织记忆')).toBeInTheDocument();
    expect(groupRequests).toBeGreaterThan(0);
  });

  it('passes type and status filters to the grouped endpoint', async () => {
    const seenUrls: string[] = [];
    server.use(
      ...agentHandlers,
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/memories/grouped', ({ request }) => {
        seenUrls.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: groupedFixture,
        });
      }),
    );
    renderPage();

    fireEvent.click(screen.getByRole('radio', { name: '按员工' }));
    await screen.findByText('分组记忆一');

    fireEvent.click(screen.getByRole('radio', { name: '规则' }));
    await vi.waitFor(() => {
      expect(seenUrls.some((q) => q.includes('type=RULE'))).toBe(true);
    });
  });

  it('keeps the timeline view on the flat list endpoint after switching back', async () => {
    server.use(
      ...agentHandlers,
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 5, scope: 'ORG', type: 'FACT', status: 'ADOPTED',
          title: '时间线记忆', contentMd: '时间线内容',
          sourceRef: null, gmtCreate: '2026-08-03',
        }],
      })),
      http.get('/api/memories/grouped', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: groupedFixture,
      })),
    );
    renderPage();
    await screen.findByText('时间线记忆');

    fireEvent.click(screen.getByRole('radio', { name: '按员工' }));
    await screen.findByText('分组记忆一');

    fireEvent.click(screen.getByRole('radio', { name: '时间线' }));

    expect(await screen.findByText('时间线记忆')).toBeInTheDocument();
    expect(screen.queryByText('分组记忆一')).not.toBeInTheDocument();
  });

  it('promotes an adopted memory to squad scope from the edit modal', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    let putBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 1, scope: 'AGENT', ownerRef: 400130, type: 'FACT', status: 'ADOPTED',
          title: '员工记忆', contentMd: '原始内容',
          sourceRef: null, gmtCreate: '2026-08-01',
        }],
      })),
      http.get('/api/agents/400130', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 400130, name: '测试员工' },
      })),
      http.put('/api/memories/1', async ({ request }) => {
        putBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 1, scope: 'SQUAD', ownerRef: 9, type: 'FACT', status: 'ADOPTED',
            title: '员工记忆', contentMd: '原始内容',
            sourceRef: null, gmtCreate: '2026-08-01',
          },
        });
      }),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /编辑/ }));

    const dialog = await screen.findByRole('dialog', { name: '编辑记忆' });
    const scopeSelect = within(dialog).getByRole('combobox', { name: '范围' });
    await userEvent.click(scopeSelect);
    await userEvent.click((await screen.findAllByText('小队'))[0]);

    const ownerInput = within(dialog).getByLabelText('小队 ID');
    await userEvent.clear(ownerInput);
    await userEvent.type(ownerInput, '9');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await vi.waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody!.scope).toBe('SQUAD');
    expect(putBody!.ownerRef).toBe(9);
    expect(putBody!.title).toBe('员工记忆');
    await vi.waitFor(() => expect(successSpy).toHaveBeenCalledWith('已提升为小队记忆'));
    successSpy.mockRestore();
  });

  it('hides the scope option when editing a non-adopted memory', async () => {
    server.use(
      http.get('/api/memories', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 2, scope: 'AGENT', ownerRef: 400130, type: 'FACT', status: 'PENDING',
          title: '待审核记忆', contentMd: '内容',
          sourceRef: null, gmtCreate: '2026-08-01',
        }],
      })),
      http.get('/api/agents/400130', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 400130, name: '测试员工' },
      })),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /编辑/ }));

    const dialog = await screen.findByRole('dialog', { name: '编辑记忆' });
    expect(within(dialog).queryByRole('combobox', { name: '范围' })).not.toBeInTheDocument();
  });
});
