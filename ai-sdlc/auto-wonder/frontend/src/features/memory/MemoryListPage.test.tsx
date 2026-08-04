import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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

  it('offers employee squad and organization scopes without repository scope', async () => {
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
    expect(screen.getAllByText('组织全局').length).toBeGreaterThan(0);
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
});
