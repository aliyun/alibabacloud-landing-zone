import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { Table } from '@/shared/theme/ThemedTable';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useState, type ReactNode } from 'react';
import { CloseOutlined, InfoCircleFilled, CloseCircleFilled, ExclamationCircleFilled } from '@ant-design/icons';
import { Alert, Button, Popconfirm, Select, Space, Tag, Tooltip, message } from 'antd';
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


function displayName(admin: PlatformAdmin): string {
  return admin.nickname || admin.username;
}

export function PlatformAdminPanel() {
  const { t } = useTranslation('platform');
  const permissionHint = t('adminPermission');
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
      message.success(i18n.t('platform:adminAdded'));
      // The write endpoints return the refreshed roster, so one round trip updates both queries.
      queryClient.setQueryData(PLATFORM_ADMINS_QUERY_KEY, list);
      await queryClient.invalidateQueries({ queryKey: PLATFORM_ADMIN_CANDIDATES_QUERY_KEY });
      setSelectedUserId(undefined);
      setKeyword('');
    },
    onError: (err: Error) => message.error(err.message || i18n.t('platform:adminAddFailed')),
  });

  const removeMutation = useMutation({
    mutationFn: removePlatformAdmin,
    onSuccess: async (list) => {
      message.success(i18n.t('platform:adminRemoved'));
      queryClient.setQueryData(PLATFORM_ADMINS_QUERY_KEY, list);
      await queryClient.invalidateQueries({ queryKey: PLATFORM_ADMIN_CANDIDATES_QUERY_KEY });
    },
    onError: (err: Error) => message.error(err.message || i18n.t('platform:adminRemoveFailed')),
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
      title: t('admin'),
      key: 'administrator',
      width: '24%',
      render: (_, admin) => (
        <span style={{ position: 'relative' }}>
          <span style={{ fontWeight: 500 }}>{displayName(admin)}</span>
          {admin.self && <Tag color="blue" style={{ position: 'absolute', marginLeft: 6, whiteSpace: 'nowrap' }}>{t('me')}</Tag>}
        </span>
      ),
    },
    {
      title: t('username'),
      dataIndex: 'username',
      width: '20%',
      ellipsis: { showTitle: false },
      render: (v: string) => <EllipsisText tooltip={v}>{v ?? '—'}</EllipsisText>,
    },
    {
      title: t('email'),
      dataIndex: 'email',
      width: '32%',
      ellipsis: { showTitle: false },
      render: (email: string | null) => <EllipsisText tooltip={email ?? undefined}>{email || '-'}</EllipsisText>,
    },
    {
      title: t('accountStatus'),
      key: 'status',
      width: '12%',
      render: (_, admin) => (
        <Tag color={admin.active ? 'success' : 'orange'}>{admin.active ? t('active') : t('deactivated')}</Tag>
      ),
    },
    {
      title: t('actions'),
      key: 'action',
      width: '12%',
      render: (_, admin) => {
        const reason = !canManage ? permissionHint : !admin.removable && admin.self ? t('selfRemoval') : !admin.removable && admins.length <= 1 ? t('lastAdmin') : admin.removeDisabledReason;
        const enabled = canManage && admin.removable;
        return withReason(
          <Popconfirm
            icon={<ExclamationCircleFilled aria-hidden="true" />}
            title={t('removeConfirm', { name: displayName(admin) })}
            okText={t('confirmRemove')}
            cancelText={t('cancel')}
            disabled={!enabled}
            onConfirm={() => handleRemove(admin.userId)}
          >
            <Button type="link" size="small" aria-label={t('remove')} danger disabled={!enabled}>{t('remove')}</Button>
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
        icon={<CloseCircleFilled aria-hidden="true" />}
        message={t('adminLoadFailed')}
        description={error instanceof Error ? error.message : t('retryLater')}
      />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleFilled aria-hidden="true" />}
        message={t('adminHint')}
      />

      <Space.Compact>
        {withReason(
          <Select
            showSearch
            filterOption={false}
            aria-label={t('searchUsers')}
            disabled={!canManage}
            value={selectedUserId}
            placeholder={t('searchUsers')}
            loading={candidatesLoading}
            style={{ width: 320 }}
            searchValue={keyword}
            onSearch={setKeyword}
            onChange={setSelectedUserId}
            options={candidates.map((candidate) => ({
              value: candidate.userId,
              label: `${candidate.nickname || candidate.username}${candidate.email ? ` (${candidate.email})` : ''}`,
            }))}
            notFoundContent={keyword.trim() ? t('noCandidates') : t('searchHint')}
          />,
          canManage ? null : permissionHint,
        )}
        {withReason(
          <Button
            type="primary"
            disabled={!canManage || selectedUserId === undefined}
            aria-label={t('addAdmin')}
            loading={addMutation.isPending}
            onClick={handleAdd}
          >
            {t('addAdmin')}
          </Button>,
          canManage ? null : permissionHint,
        )}
        {/* ponytail: rc-select hides its clear parent from accessibility; keep a public keyboard button until upstream exposes an accessible clear API. */}
        {(selectedUserId !== undefined || keyword) && <Button aria-label={t('clearUser')} icon={<CloseOutlined aria-hidden="true" />} disabled={!canManage} onClick={() => { setSelectedUserId(undefined); setKeyword(''); }} />}
      </Space.Compact>

      <Table
        rowKey="userId"
        columns={columns}
        dataSource={admins}
        loading={isLoading || removeMutation.isPending}
        pagination={false}
        scroll={{ x: 920 }}
      />
    </div>
  );
}
