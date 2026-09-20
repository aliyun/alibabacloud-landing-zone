import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ProjectBackupsPage } from './ProjectBackupsPage';
import { useAuthStore } from '@/shared/auth/store';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });
const backup = { id: 'backup-1', status: 'SUCCEEDED', createdAt: '2026-09-10 12:00:00', ossRef: 'bucket/project-backups/1/backup-1.zip', sizeBytes: 1024 };
function renderPage() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ProjectBackupsPage />
  </QueryClientProvider>);
}

describe('ProjectBackupsPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'project', description: '' }, 'ADMIN');
  });

  it('creates a backup and refreshes history', async () => {
    let created = false;
    server.use(
      http.get('/api/workspaces/current/backups', () => ok(created ? [backup] : [])),
      http.post('/api/workspaces/current/backups', () => { created = true; return ok(backup); }),
    );
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /立即备份到云端/ }));
    expect(await screen.findByText(backup.ossRef)).toBeInTheDocument();
    expect(created).toBe(true);
    expect(screen.getByRole('button', { name: /下载/ })).toBeEnabled();
  });

  it('does not fetch backup history for non-admin members', async () => {
    const fetch = vi.fn(() => ok([]));
    server.use(http.get('/api/workspaces/current/backups', fetch));
    useAuthStore.getState().setAccessLevel('READ_ONLY');
    renderPage();
    expect(screen.getByText('仅项目管理员可以创建、查看和下载备份。')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /立即备份/ })).not.toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('shows failed backups and prevents downloading them', async () => {
    server.use(http.get('/api/workspaces/current/backups', () => ok([
      { ...backup, status: 'FAILED', ossRef: null, errorMessage: '技能包文件缺失' },
    ])));
    renderPage();
    expect(await screen.findByText('技能包文件缺失')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /下载/ })).toBeDisabled();
  });

  it('requests a fresh signed link only when downloading', async () => {
    const sign = vi.fn(() => ok({ url: 'https://example.com/backup.zip' }));
    server.use(
      http.get('/api/workspaces/current/backups', () => ok([backup])),
      http.get('/api/workspaces/current/backups/backup-1/download', sign),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderPage();
    await screen.findByText(backup.ossRef);
    expect(sign).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /下载/ }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(sign).toHaveBeenCalledOnce();
    click.mockRestore();
  });
});
