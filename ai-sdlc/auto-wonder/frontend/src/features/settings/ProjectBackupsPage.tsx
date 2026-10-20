import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useState } from 'react';
import { Alert, Button, Card, Space, Tag, Typography, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { DownloadOutlined, CloudUploadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { OffsetPagination } from '@/shared/ui/OffsetPagination';

export interface ProjectBackup {
  id: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  ossRef?: string;
  sizeBytes?: number;
  sha256?: string;
  errorMessage?: string;
  createdAt: string;
  finishedAt?: string;
}

export function ProjectBackupsPage() {
  const workspaceId = useAuthStore((s) => s.currentWorkspace?.id);
  const isAdmin = useAuthStore((s) => s.accessLevel === 'ADMIN');
  // Remount pagination and mutation state when the selected project changes.
  return <ProjectBackupsPanel key={workspaceId} workspaceId={workspaceId} isAdmin={isAdmin} />;
}

function ProjectBackupsPanel({ workspaceId, isAdmin }: { workspaceId?: number; isAdmin: boolean }) {
  const { t } = useTranslation('settings');
  const [page, setPage] = useState(1);
  const [backupPageSize, setBackupPageSize] = useState(10);
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const queryKey = ['project-backups', workspaceId, backupPageSize];
  const query = useQuery({
    queryKey: [...queryKey, page],
    queryFn: async () => (await apiClient.get<ProjectBackup[]>('/api/workspaces/current/backups', {
      params: { page, size: backupPageSize },
    })).data,
    enabled: isAdmin && Boolean(workspaceId),
    refetchInterval: 5000,
  });
  const create = useMutation({
    mutationFn: async () => (await apiClient.post<ProjectBackup>('/api/workspaces/current/backups', {}, {
      timeout: 300000,
    })).data,
    onSuccess: () => { message.success(i18n.t('settings:backupCreated')); setPage(1); },
    onSettled: () => queryClient.invalidateQueries({ queryKey }),
  });
  const download = useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.get<{ url: string }>(`/api/workspaces/current/backups/${id}/download`);
      const link = document.createElement('a');
      link.href = data.url;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.click();
    },
  });
  return <Card className="aw-content-card" title={<PageHeading title={t('backups')} />}
    extra={isAdmin && <Space>
      <Button type="primary" icon={<CloudUploadOutlined aria-hidden="true" />} aria-label={t('backupNow')} loading={create.isPending} onClick={() =>
        runAccessCommand('ADMIN', t('createBackup'), () => create.mutate())}>{t('backupNow')}</Button>
      <Button aria-label={t('refresh')} onClick={() => query.refetch()} loading={query.isFetching}>{t('refresh')}</Button>
    </Space>}>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" showIcon message={t('backupInfo')} description={t('backupDescription')} />
      {!isAdmin ? <Alert type="warning" message={t('backupAdminOnly')} /> : <>
        <Typography.Text type="secondary">{t('backupArchiveHint')}</Typography.Text>
        {query.isError && <Alert type="error" message={t('backupLoadFailed')} />}
        {create.isError && <Alert type="error" message={t('backupCreateFailed')} />}
        <Table<ProjectBackup> rowKey="id" loading={query.isLoading} dataSource={query.data ?? []} pagination={false}
          scroll={{ x: 850 }} columns={[
            { title: t('createdAt'), dataIndex: 'createdAt', render: (value: string) => value && !Number.isNaN(new Date(value).getTime()) ? formatDateTime(value, { dateStyle: 'medium', timeStyle: 'medium' }) : value || '-' },
            { title: t('status'), dataIndex: 'status', render: (status: ProjectBackup['status']) => {
              const label = status === 'RUNNING' ? t('backupRunning') : status === 'SUCCEEDED' ? t('backupSucceeded') : status === 'FAILED' ? t('backupFailed') : status;
              const color = status === 'RUNNING' ? 'processing' : status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : undefined;
              return <Tag color={color}>{label}</Tag>;
            } },
            { title: t('size'), dataIndex: 'sizeBytes', render: (size?: number) => size == null ? '-' : t('backupSize', { size: formatNumber(size / 1024, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }) },
            { title: t('backupLocation'), render: (_, row) => row.errorMessage || row.ossRef || '-' },
            { title: t('actions'), render: (_, row) => <Button aria-label={t('download')} icon={<DownloadOutlined aria-hidden="true" />} disabled={row.status !== 'SUCCEEDED'}
              loading={download.isPending && download.variables === row.id}
              onClick={() => runAccessCommand('ADMIN', t('downloadBackup'), () => download.mutate(row.id))}>{t('download')}</Button> },
          ]} />
        <OffsetPagination
          storageKey="autowonder.projectBackups.pageSize"
          offset={(page - 1) * backupPageSize}
          loadedCount={query.data?.length ?? 0}
          onOffsetChange={(next) => setPage(Math.floor(next / backupPageSize) + 1)}
          onPageSizeChange={(next) => { setBackupPageSize(next); setPage(1); }}
        />
      </>}
    </Space>
  </Card>;
}
