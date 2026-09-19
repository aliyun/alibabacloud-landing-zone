import { useState, type ReactNode } from 'react';
import { Alert, Button, Popconfirm, Select, Space, Table, Tag, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  PLATFORM_ADMIN_CANDIDATES_QUERY_KEY,
  PLATFORM_ADMINS_QUERY_KEY,
  addPlatformAdmin,
  getPlatformAdmins,
  removePlatformAdmin,
  searchPlatformAdminCandidates,
  type PlatformAdmin,
} from './adminApi';

const NOT_PLATFORM_ADMIN = '仅平台管理员可操作';

function displayName(admin: PlatformAdmin): string {
  return admin.nickname || admin.username;
}

export function PlatformAdminPanel() {
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState('');
  const [selectedUserId, setSelectedUserId] = useState<number>();

  const { data, error, isLoading } = useQuery({
    queryKey: PLATFORM_ADMINS_QUERY_KEY,
    queryFn: getPlatformAdmins,
    retry: false,
  });

  const admins = data?.admins ?? [];
  const canManage = Boolean(data?.canManage);

  // Gated on canManage: the candidate endpoint rejects a caller who may not promote anybody, so
  // firing it for a read-only viewer would only raise a permission toast they cannot act on.
  const { data: candidates = [], isFetching: candidatesLoading } = useQuery({
    queryKey: [...PLATFORM_ADMIN_CANDIDATES_QUERY_KEY, keyword],
    queryFn: () => searchPlatformAdminCandidates(keyword),
    enabled: canManage,
    retry: false,
  });

  const addMutation = useMutation({
    mutationFn: addPlatformAdmin,
    onSuccess: async (list) => {
      message.success('已添加平台管理员');
      // The write endpoints return the refreshed roster, so one round trip updates both queries.
      queryClient.setQueryData(PLATFORM_ADMINS_QUERY_KEY, list);
      await queryClient.invalidateQueries({ queryKey: PLATFORM_ADMIN_CANDIDATES_QUERY_KEY });
      setSelectedUserId(undefined);
      setKeyword('');
    },
    onError: (err: Error) => message.error(err.message || '添加平台管理员失败'),
  });

  const removeMutation = useMutation({
    mutationFn: removePlatformAdmin,
    onSuccess: async (list) => {
      message.success('已移除平台管理员');
      queryClient.setQueryData(PLATFORM_ADMINS_QUERY_KEY, list);
      await queryClient.invalidateQueries({ queryKey: PLATFORM_ADMIN_CANDIDATES_QUERY_KEY });
    },
    onError: (err: Error) => message.error(err.message || '移除平台管理员失败'),
  });

  const handleAdd = () => {
    if (selectedUserId === undefined) return;
    addMutation.mutate(selectedUserId);
  };

  // Named rather than inlined in onConfirm: the workspace access ladder deliberately does not apply
  // here. /api/platform/admins is a global platform capability whose controller carries no
  // @RequireWorkspaceAccess (asserted by WorkspaceAccessAnnotationCoverageTest), and useAccessCommand
  // reads the selected workspace's accessLevel, so it would lock out a platform admin who has no
  // workspace selected. The authoritative re-check is SystemAdminService.requireSystemAdmin, which
  // runs on the server at confirm time and whose refusal this panel surfaces verbatim.
  const handleRemove = (userId: number) => removeMutation.mutate(userId);

  // antd Tooltip does not fire mouse events on a disabled control, so the wrapper span is required
  // for the reason to be reachable.
  const withReason = (control: ReactNode, reason: string | null) => {
    if (!reason) return <>{control}</>;
    return <Tooltip title={reason}><span>{control}</span></Tooltip>;
  };

  const columns: ColumnsType<PlatformAdmin> = [
    {
      title: '用户',
      key: 'user',
      render: (_, admin) => (
        <div>
          <Space size={6}>
            <span style={{ fontWeight: 500 }}>{displayName(admin)}</span>
            {admin.self && <Tag color="blue">我</Tag>}
            {!admin.active && <Tag color="orange">已停用</Tag>}
          </Space>
          <div style={{ fontSize: 12, color: '#666' }}>{admin.email || '-'}</div>
        </div>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, admin) => {
        const reason = canManage ? admin.removeDisabledReason : NOT_PLATFORM_ADMIN;
        const enabled = canManage && admin.removable;
        return withReason(
          <Popconfirm
            title={`确定移除 ${displayName(admin)} 的平台管理员权限？`}
            okText="确定移除"
            cancelText="取消"
            disabled={!enabled}
            onConfirm={() => handleRemove(admin.userId)}
          >
            <Button type="link" size="small" danger disabled={!enabled}>移除</Button>
          </Popconfirm>,
          enabled ? null : reason,
        );
      },
    },
  ];

  if (error) {
    return (
      <Alert
        type="error"
        showIcon
        message="平台管理员加载失败"
        description={error instanceof Error ? error.message : '请稍后重试'}
      />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <Alert
        type="info"
        showIcon
        message="平台管理员可以管理平台配置、协作通知和所有工作空间（含回收站）。管理员不可移除自己，且至少保留一名。"
      />

      <Space.Compact>
        {withReason(
          <Select
            showSearch
            allowClear
            filterOption={false}
            aria-label="搜索可添加的用户"
            disabled={!canManage}
            value={selectedUserId}
            placeholder="搜索可添加的用户"
            loading={candidatesLoading}
            style={{ width: 320 }}
            onSearch={setKeyword}
            onClear={() => {
              setSelectedUserId(undefined);
              setKeyword('');
            }}
            onChange={setSelectedUserId}
            options={candidates.map((candidate) => ({
              value: candidate.userId,
              label: `${candidate.nickname || candidate.username}${candidate.email ? ` (${candidate.email})` : ''}`,
            }))}
            notFoundContent={keyword.trim() ? '暂无可添加的用户' : '输入姓名、用户名或邮箱搜索'}
          />,
          canManage ? null : NOT_PLATFORM_ADMIN,
        )}
        {withReason(
          <Button
            type="primary"
            disabled={!canManage || selectedUserId === undefined}
            loading={addMutation.isPending}
            onClick={handleAdd}
          >
            添加管理员
          </Button>,
          canManage ? null : NOT_PLATFORM_ADMIN,
        )}
      </Space.Compact>

      <Table
        rowKey="userId"
        columns={columns}
        dataSource={admins}
        loading={isLoading || removeMutation.isPending}
        pagination={false}
      />
    </div>
  );
}
