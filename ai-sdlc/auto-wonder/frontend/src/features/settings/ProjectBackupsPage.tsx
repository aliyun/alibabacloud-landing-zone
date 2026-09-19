import { useState } from 'react';
import { Alert, Button, Card, Space, Table, Tag, Typography, message } from 'antd';
import { DownloadOutlined, CloudUploadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

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
  const [page, setPage] = useState(1);
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const queryKey = ['project-backups', workspaceId];
  const query = useQuery({
    queryKey: [...queryKey, page],
    queryFn: async () => (await apiClient.get<ProjectBackup[]>('/api/workspaces/current/backups', {
      params: { page, size: 20 },
    })).data,
    enabled: isAdmin && Boolean(workspaceId),
    refetchInterval: 5000,
  });
  const create = useMutation({
    mutationFn: async () => (await apiClient.post<ProjectBackup>('/api/workspaces/current/backups', {}, {
      timeout: 300000,
    })).data,
    onSuccess: () => { message.success('项目配置已备份到对象存储'); setPage(1); },
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
  return <Card title="项目配置备份">
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="备份当前项目的配置与知识" description="包含 SDLC、数字员工及版本、小队、仓库关系、记忆、技能及技能包、成员权限、集成与定时任务配置。排除工单、执行记录、会话、审核、日志与用量数据。密钥仅保留凭据引用，迁移后需重新绑定。" />
      {!isAdmin ? <Alert type="warning" message="仅项目管理员可以创建、查看和下载备份。" /> : <>
        <Space>
          <Button type="primary" icon={<CloudUploadOutlined />} loading={create.isPending} onClick={() =>
            runAccessCommand('ADMIN', '创建项目备份', () => create.mutate())}>立即备份到云端</Button>
          <Button onClick={() => query.refetch()} loading={query.isFetching}>刷新</Button>
        </Space>
        <Typography.Text type="secondary">ZIP 包包含配置 JSON、技能包和校验清单。备份原始数据上限 64 MiB；下载链接有效期 5 分钟。</Typography.Text>
        {query.isError && <Alert type="error" message="备份记录加载失败，请重试；首次使用前需执行备份建表 SQL。" />}
        {create.isError && <Alert type="error" message="备份请求未完成，请刷新记录确认结果后重试。" />}
        <Table<ProjectBackup> rowKey="id" loading={query.isLoading} dataSource={query.data ?? []} pagination={false}
          scroll={{ x: 850 }} columns={[
            { title: '创建时间', dataIndex: 'createdAt' },
            { title: '状态', dataIndex: 'status', render: (status: ProjectBackup['status']) =>
              <Tag color={{ RUNNING: 'processing', SUCCEEDED: 'success', FAILED: 'error' }[status]}>
                {{ RUNNING: '备份中', SUCCEEDED: '成功', FAILED: '失败' }[status]}</Tag> },
            { title: '大小', dataIndex: 'sizeBytes', render: (size?: number) => size == null ? '-' : `${(size / 1024).toFixed(1)} KiB` },
            { title: '存储位置 / 失败原因', render: (_, row) => row.errorMessage || row.ossRef || '-' },
            { title: '操作', render: (_, row) => <Button icon={<DownloadOutlined />} disabled={row.status !== 'SUCCEEDED'}
              loading={download.isPending && download.variables === row.id}
              onClick={() => runAccessCommand('ADMIN', '下载项目备份', () => download.mutate(row.id))}>下载</Button> },
          ]} />
        <Space>
          <Button disabled={page === 1 || query.isFetching} onClick={() => setPage(page - 1)}>上一页</Button>
          <span>第 {page} 页</span>
          <Button disabled={(query.data?.length ?? 0) < 20 || query.isFetching} onClick={() => setPage(page + 1)}>下一页</Button>
        </Space>
      </>}
    </Space>
  </Card>;
}
