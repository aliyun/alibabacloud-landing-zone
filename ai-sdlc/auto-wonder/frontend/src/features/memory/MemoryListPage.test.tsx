import { describe, expect, it } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { MemoryListPage } from './MemoryListPage';

function renderPage(level: 'READ_ONLY' | 'READ_WRITE' | 'ADMIN' = 'ADMIN') {
  useAuthStore.setState({ accessLevel: level });
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><MemoryListPage /></MemoryRouter>
  </QueryClientProvider>);
}

function handlers(permission: 'READ' | 'WRITE' | 'ADMIN' = 'ADMIN') {
  server.use(
    http.get('/api/memory-stores/directory', () => HttpResponse.json({ success: true, data: { owners: [
      { scope: 'AGENT', ownerRef: 42, name: '社区发布工程师', squadNames: ['社区发布小队'], storeId: 11, permission, currentRevision: 8, canCreate: false },
      { scope: 'AGENT', ownerRef: 43, name: '尚未执行的测试工程师', squadNames: ['社区发布小队'], canCreate: permission === 'ADMIN' },
      { scope: 'ORG', ownerRef: 0, name: '组织共享记忆', squadNames: [], canCreate: permission === 'ADMIN' },
    ], migration: { migrated: 3, pending: 0 } } })),
    http.get('/api/memory-stores', () => HttpResponse.json({ success: true, data: [{ id: 11, scope: 'AGENT', ownerRef: 42, name: '社区发布工程师', currentRevision: 8, version: 2, status: 'ACTIVE', permission }] })),
    http.get('/api/memory-stores/11/documents', () => HttpResponse.json({ success: true, data: [
      { id: 1, storeId: 11, path: 'MEMORY.md', contentMd: '- [发布](project_release.md) — 发布规则', contentSha256: 'a', byteSize: 50, modifiedAt: '2026-09-17', version: 3 },
      { id: 2, storeId: 11, path: 'project_release.md', title: '社区发布检查', memoryType: 'feedback', description: '发布时参考', body: '需要 E2E 验证', contentMd: '需要 E2E 验证', contentSha256: 'b', byteSize: 20, modifiedAt: '2026-09-17', version: 1 },
    ] })),
    http.get('/api/memory-stores/11/history', () => HttpResponse.json({ success: true, data: [] })),
    http.get('/api/memory-stores/11/acls', () => HttpResponse.json({ success: true, data: [] })),
    http.get('/api/memory-imports', () => HttpResponse.json({ success: true, data: [] })),
  );
}

describe('MemoryListPage', () => {
  it('preserves master pagination for semantic topics and history', async () => {
    handlers();
    server.use(
      http.get('/api/memory-stores/11/documents', () => HttpResponse.json({ success: true, data:
        Array.from({ length: 11 }, (_, i) => ({ id: i + 1, storeId: 11, path: `topic_${String(i).padStart(2, '0')}.md`, title: `分页主题${i}`, contentMd: '正文', byteSize: 6, version: 1 })) })),
      http.get('/api/memory-stores/11/history', () => HttpResponse.json({ success: true, data:
        Array.from({ length: 11 }, (_, i) => ({ id: i + 1, operation: 'UPDATE', path: `history_${i}.md`, storeRevision: i + 1 })) })),
    );
    renderPage();
    expect(await screen.findByRole('button', { name: '分页主题0' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '分页主题10' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByTitle('2'));
    expect(await screen.findByRole('button', { name: '分页主题10' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: '变更历史' }));
    expect(await screen.findByText('UPDATE · history_0.md')).toBeInTheDocument();
    expect(screen.queryByText('UPDATE · history_10.md')).not.toBeInTheDocument();
  });

  it('keeps the access dialog open when required fields are invalid', async () => {
    handlers(); renderPage();
    await screen.findByText('社区发布工程师');
    await userEvent.click(screen.getByRole('tab', { name: '访问权限' }));
    await userEvent.click(await screen.findByRole('button', { name: '添加访问权限' }));
    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByRole('dialog', { name: '新增记忆权限' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('对象编号')).toHaveAttribute('aria-invalid', 'true'));
  });

  it('switches workspace without retaining the previous memory body or open viewer', async () => {
    handlers();
    useAuthStore.setState({ currentWorkspace: { id: 10, name: '空间一', description: '' } });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '社区发布检查' }));
    expect(screen.getByText('需要 E2E 验证')).toBeInTheDocument();
    server.use(http.get('/api/memory-stores/directory', () => HttpResponse.json({ success: true, data: {
      owners: [{ scope: 'AGENT', ownerRef: 60, name: '第二空间工程师', squadNames: [], canCreate: true }],
    } })));
    act(() => useAuthStore.setState({ currentWorkspace: { id: 20, name: '空间二', description: '' } }));
    expect(await screen.findByText('第二空间工程师')).toBeInTheDocument();
    expect(screen.queryByText('社区发布工程师')).not.toBeInTheDocument();
    expect(screen.queryByText('需要 E2E 验证')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('browses a server-backed store and its index-first documents without review concepts', async () => {
    handlers(); renderPage();
    expect(await screen.findByText('社区发布工程师')).toBeInTheDocument();
    expect(await screen.findByText('MEMORY.md')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '社区发布检查' })).toBeInTheDocument();
    expect(screen.getByText('记忆库修订版本 8')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '记忆内容 (1)' })).toBeInTheDocument();
    expect(screen.queryByText(/Claude Code 兼容/)).not.toBeInTheDocument();
    expect(screen.queryByText(/待审核|采纳|审核台/)).not.toBeInTheDocument();
  });

  it('opens the document editor with line and byte measurements', async () => {
    handlers(); renderPage();
    await screen.findByText('MEMORY.md');
    await userEvent.click(screen.getAllByRole('button', { name: /编辑/ })[0]);
    expect(await screen.findByRole('dialog', { name: '编辑记忆文档' })).toBeInTheDocument();
    expect(screen.getByText(/1 行/)).toBeInTheDocument();
  });

  it('keeps shared read-only documents visible and disables destructive mutation', async () => {
    handlers('READ'); renderPage('READ_WRITE');
    await screen.findByText('MEMORY.md');
    expect(screen.getAllByRole('button', { name: /删除/ })[0]).toBeDisabled();
  });

  it('provides ACL maintenance and import lifecycle controls to administrators', async () => {
    let requestedStatus = '';
    handlers();
    server.use(
      http.get('/api/memory-stores/11/acls', () => HttpResponse.json({ success: true, data: [{ id: 5, subjectType: 'AGENT', subjectRef: '42', permission: 'WRITE' }] })),
      http.get('/api/memory-imports', () => HttpResponse.json({ success: true, data: [{ id: 21, agentId: 42, targetStoreId: 11, providerFamily: 'qoder', logicalPath: 'MEMORY.md', installationFingerprint: 'host-a', status: 'ACTIVE', version: 3 }] })),
      http.get('/api/memory-imports/21/receipts', () => HttpResponse.json({ success: true, data: [{ id: 31, sourceId: 21, sanitizedContentSha256: 'a', outcome: 'SUCCEEDED', redactionCount: 0, gmtCreate: '2026-09-17' }] })),
      http.put('/api/memory-imports/21/status', async ({ request }) => { requestedStatus = ((await request.json()) as { status: string }).status; return HttpResponse.json({ success: true, data: null }); }),
    );
    renderPage();
    await screen.findByText('社区发布工程师');
    await userEvent.click(screen.getByRole('tab', { name: '访问权限' }));
    expect(await screen.findByRole('button', { name: '添加访问权限' })).toBeInTheDocument();
    expect(await screen.findByText('数字人 · 42')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: '本地来源' }));
    expect(await screen.findByText('qoder · MEMORY.md')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '暂停' }));
    await waitFor(() => expect(requestedStatus).toBe('PAUSED'));
  });

  it('shows owners without stores and allows creating memory without manual file paths', async () => {
    handlers(); renderPage();
    await userEvent.click(await screen.findByText('尚未执行的测试工程师'));
    expect(screen.getByText('暂无记忆，可通过“新增记忆”添加')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /新增记忆/ }));
    expect(await screen.findByLabelText('归属范围')).toBeInTheDocument();
    expect(screen.getByLabelText('记忆类型')).toBeInTheDocument();
    expect(screen.queryByLabelText('路径')).not.toBeInTheDocument();
  });

  it('opens topic body as readable content rather than requiring edit', async () => {
    handlers(); renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '社区发布检查' }));
    expect(await screen.findByRole('dialog', { name: '社区发布检查' })).toBeInTheDocument();
    expect(screen.getByText('需要 E2E 验证')).toBeInTheDocument();
  });

  it('filters local sources to selected agent and explains missing files', async () => {
    handlers();
    server.use(http.get('/api/memory-imports', () => HttpResponse.json({ success: true, data: [
      { id: 21, agentId: 42, targetStoreId: 11, providerFamily: 'qoder', logicalPath: 'MEMORY.md', status: 'MISSING', version: 1 },
      { id: 22, agentId: 99, targetStoreId: 22, providerFamily: 'qoder', logicalPath: 'other-agent.md', status: 'ACTIVE', version: 1 },
    ] })));
    renderPage(); await screen.findByText('社区发布工程师');
    await userEvent.click(screen.getByRole('tab', { name: '本地来源' }));
    expect(await screen.findByText(/未找到本地文件不代表平台记忆丢失/)).toBeInTheDocument();
    expect(screen.queryByText('qoder · other-agent.md')).not.toBeInTheDocument();
    await userEvent.click(screen.getByText(/未找到本地文件（1）/));
    expect(await screen.findByText('qoder · MEMORY.md')).toBeInTheDocument();
  });
});
