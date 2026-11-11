import { useTranslation } from 'react-i18next';
import { useEffect, useState } from 'react';
import { Alert, Button, Input, Modal, Select, Space, Tag, Typography, message } from 'antd';
import { CloseCircleFilled, InfoCircleFilled, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { Table } from '@/shared/theme/ThemedTable';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { formatDateTime } from '@/shared/i18n/format';
import { useLocale } from '@/shared/i18n/LocaleProvider';
import {
  USER_ADMIN_LIST_QUERY_KEY,
  getUserAdminList,
  reviewUserRegistration,
  updateUserWorkspaceCreationPermission,
  userAdminDetailQueryKey,
  userAdminListQueryKey,
  REJECT_DECISION,
  type RegistrationReviewDecision,
  type UserAdminListParams,
  type UserAdminPermissionFilter,
  type UserAdminStatus,
  type UserAdminStatusFilter,
  type UserAdminTypeFilter,
  type UserAdminUser,
} from './userAdminApi';
import { UserDetailDrawer, type UserDetailInitialAction } from './UserDetailDrawer';
import { WorkspaceCreationPermissionSwitch } from './WorkspaceCreationPermissionSwitch';

const { Text } = Typography;

const EMPTY_TEXT = '—';
const PAGE_SIZE = 20;

const statusKeys = {
  NORMAL: { labelKey: 'statusNormal', color: 'success' },
  PENDING_REVIEW: { labelKey: 'statusPendingReview', color: 'gold' },
  DELETED: { labelKey: 'statusDeleted', color: 'default' },
  DEACTIVATED: { labelKey: 'statusDeactivated', color: 'orange' },
} as const;

export function UserManagementPanel() {
  const { t } = useTranslation('userManagement');
  const { locale } = useLocale();
  const filterWidths = locale === 'zh-CN'
    ? { status: 104, permission: 104, type: 132 }
    : { status: 146, permission: 146, type: 144 };
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<UserAdminStatusFilter>('ALL');
  const [typeFilter, setTypeFilter] = useState<UserAdminTypeFilter>('ALL');
  const [permissionFilter, setPermissionFilter] = useState<UserAdminPermissionFilter>('ALL');
  const [page, setPage] = useState(1);
  const [drawer, setDrawer] = useState<{ userId: number; initialAction?: UserDetailInitialAction } | null>(null);
  const [reviewTarget, setReviewTarget] = useState<UserAdminUser | null>(null);

  // 通知深链：合法查询参数优先于标签页记忆；清空旧搜索与页码，默认筛选待审核。
  useEffect(() => {
    const status = searchParams.get('status');
    const userId = Number(searchParams.get('userId'));
    if (status === 'PENDING_REVIEW') {
      setStatusFilter('PENDING_REVIEW');
      setSearchInput('');
      setKeyword('');
      setPage(1);
    }
    if (Number.isFinite(userId) && userId > 0) {
      setDrawer({ userId });
    }
  }, [searchParams]);

  const params: UserAdminListParams = {
    keyword,
    status: statusFilter,
    type: typeFilter,
    permission: permissionFilter,
    page,
    size: PAGE_SIZE,
  };

  const listQuery = useQuery({
    queryKey: userAdminListQueryKey(params),
    queryFn: () => getUserAdminList(params),
    placeholderData: (prev) => prev,
    retry: false,
  });

  const data = listQuery.data;
  const rows = data?.list ?? [];

  const openDrawer = (userId: number, initialAction?: UserDetailInitialAction) => {
    setDrawer({ userId, initialAction });
  };

  const handleChanged = () => {
    void queryClient.invalidateQueries({ queryKey: USER_ADMIN_LIST_QUERY_KEY });
  };

  const reviewMutation = useMutation({
    mutationFn: ({ userId, decision }: { userId: number; decision: RegistrationReviewDecision }) =>
      reviewUserRegistration(userId, decision),
    onSuccess: (_updated, variables) => {
      message.success(variables.decision === REJECT_DECISION ? t('rejected') : t('approved'));
      setReviewTarget(null);
      handleChanged();
    },
    onError: (error: Error) => {
      message.error(error.message || t('reviewFailed'));
      handleChanged();
    },
  });

  const permissionMutation = useMutation({
    mutationFn: ({ userId, allowed }: { userId: number; allowed: boolean }) =>
      updateUserWorkspaceCreationPermission(userId, allowed),
    onSuccess: (updated) => {
      message.success(t('permissionUpdated'));
      queryClient.setQueryData(userAdminDetailQueryKey(updated.id), updated);
      handleChanged();
    },
    onError: (error: Error) => {
      message.error(error.message || t('permissionUpdateFailed'));
      handleChanged();
    },
  });

  const renderDateTime = (value: string | null) => (
    value ? formatDateTime(value, { dateStyle: 'medium', timeStyle: 'medium' }) : EMPTY_TEXT
  );

  const renderStatus = (status: UserAdminStatus) => {
    const meta = Object.prototype.hasOwnProperty.call(statusKeys, status) ? statusKeys[status] : null;
    return meta ? <Tag color={meta.color}>{t(meta.labelKey)}</Tag> : <Tag>{status}</Tag>;
  };

  const columns: ColumnsType<UserAdminUser> = [
    {
      title: t('colUsername'),
      dataIndex: 'username',
      width: 140,
      render: (value: string) => <EllipsisText tooltip={value || undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colNickname'),
      dataIndex: 'nickname',
      width: 120,
      render: (value: string | null) => <EllipsisText tooltip={value ?? undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colEmail'),
      dataIndex: 'email',
      width: 180,
      render: (value: string | null) => <EllipsisText tooltip={value ?? undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colPhone'),
      dataIndex: 'phone',
      width: 130,
      render: (value: string | null) => <EllipsisText tooltip={value ?? undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colStatus'),
      key: 'status',
      width: 110,
      render: (_, record) => renderStatus(record.status),
    },
    {
      title: t('colCanCreate'),
      key: 'canCreate',
      width: 200,
      render: (_, record) => (
        <WorkspaceCreationPermissionSwitch
          user={record}
          pending={reviewMutation.isPending || permissionMutation.isPending}
          onChange={(allowed) => permissionMutation.mutate({ userId: record.id, allowed })}
        />
      ),
    },
    {
      title: t('colType'),
      key: 'type',
      width: 120,
      render: (_, record) => (
        record.isAdmin ? <Tag color="blue">{t('typeAdmin')}</Tag> : <Text type="secondary">{t('typeUser')}</Text>
      ),
    },
    {
      title: t('colCreatedAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (value: string | null) => renderDateTime(value),
    },
    {
      title: t('colActions'),
      key: 'actions',
      width: 220,
      fixed: 'right',
      render: (_, record) => {
        const pendingReview = record.status === 'PENDING_REVIEW';
        return (
        <Space size={4} wrap>
          <Button type="link" size="small" aria-label={t('detail')} onClick={() => openDrawer(record.id)}>
            {t('detail')}
          </Button>
          {pendingReview ? (
            <Button
              type="link"
              size="small"
              aria-label={t('review')}
              onClick={() => setReviewTarget(record)}
            >
              {t('review')}
            </Button>
          ) : null}
          {record.editable ? (
            <Button
              type="link"
              size="small"
              danger
              aria-label={t('deleteUser')}
              onClick={() => openDrawer(record.id, 'delete')}
            >
              {t('deleteUser')}
            </Button>
          ) : null}
          {record.restorable ? (
            <Button
              type="link"
              size="small"
              aria-label={t('restoreUser')}
              onClick={() => openDrawer(record.id, 'restore')}
            >
              {t('restoreUser')}
            </Button>
          ) : null}
        </Space>
        );
      },
    },
  ];

  if (listQuery.isError) {
    return (
      <Alert
        type="error"
        showIcon
        icon={<CloseCircleFilled aria-hidden="true" />}
        message={t('loadFailed')}
        description={listQuery.error instanceof Error ? listQuery.error.message : t('retryLater')}
        action={
          <Button size="small" onClick={() => listQuery.refetch()} loading={listQuery.isFetching}>
            {t('retry')}
          </Button>
        }
      />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <Alert type="info" showIcon icon={<InfoCircleFilled aria-hidden="true" />} message={t('totalAll', { total: data?.totalAll ?? 0 })} />

      <Space wrap>
        <Input.Search
          aria-label={t('search')}
          placeholder={t('searchPlaceholder')}
          style={{ width: 320 }}
          allowClear
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          onSearch={(value) => {
            setKeyword(value.trim());
            setPage(1);
          }}
        />
        <Select<UserAdminStatusFilter>
          aria-label={t('statusFilter')}
          value={statusFilter}
          style={{ width: filterWidths.status }}
          onChange={(value) => {
            setStatusFilter(value);
            setPage(1);
          }}
          options={[
            { value: 'ALL', label: t('statusAll') },
            { value: 'NORMAL', label: t('statusNormal') },
            { value: 'PENDING_REVIEW', label: t('statusPendingReview') },
            { value: 'DELETED', label: t('statusDeleted') },
            { value: 'DEACTIVATED', label: t('statusDeactivated') },
          ]}
        />
        <Select<UserAdminPermissionFilter>
          aria-label={t('permissionFilter')}
          value={permissionFilter}
          style={{ width: filterWidths.permission }}
          onChange={(value) => {
            setPermissionFilter(value);
            setPage(1);
          }}
          options={[
            { value: 'ALL', label: t('permissionAll') },
            { value: 'ALLOWED', label: t('permissionAllowed') },
            { value: 'BLOCKED', label: t('permissionBlocked') },
          ]}
        />
        <Select<UserAdminTypeFilter>
          aria-label={t('typeFilter')}
          value={typeFilter}
          style={{ width: filterWidths.type }}
          onChange={(value) => {
            setTypeFilter(value);
            setPage(1);
          }}
          options={[
            { value: 'ALL', label: t('typeAll') },
            { value: 'USER', label: t('typeUser') },
            { value: 'ADMIN', label: t('typeAdmin') },
          ]}
        />
        <Button
          icon={<ReloadOutlined aria-hidden="true" />}
          onClick={() => {
            setSearchInput('');
            setKeyword('');
            setStatusFilter('ALL');
            setTypeFilter('ALL');
            setPermissionFilter('ALL');
            setPage(1);
          }}
        >
          {t('resetFilters')}
        </Button>
      </Space>

      <Table<UserAdminUser>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={listQuery.isLoading}
        scroll={{ x: 1480 }}
        onRow={(record) =>
          record.status === 'DELETED' ? { style: { color: 'var(--aw-text-tertiary)', opacity: 0.72 } } : {}
        }
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total: data?.total ?? 0,
          showSizeChanger: false,
          showTotal: (total) => t('filteredTotal', { total }),
          onChange: setPage,
        }}
        locale={{ emptyText: t('empty') }}
      />

      <UserDetailDrawer
        userId={drawer?.userId ?? null}
        open={drawer != null}
        initialAction={drawer?.initialAction}
        onClose={() => setDrawer(null)}
        onChanged={handleChanged}
      />

      <Modal
        open={reviewTarget !== null}
        title={reviewTarget ? t('reviewTitle', { username: reviewTarget.username }) : t('review')}
        onCancel={() => setReviewTarget(null)}
        footer={[
          <Button key="cancel" onClick={() => setReviewTarget(null)}>{t('cancel')}</Button>,
          <Button
            key="reject"
            danger
            loading={reviewMutation.isPending}
            onClick={() => reviewTarget
              && reviewMutation.mutate({ userId: reviewTarget.id, decision: 'REJECT' })}
          >
            {t('reject')}
          </Button>,
          <Button
            key="approve"
            type="primary"
            loading={reviewMutation.isPending}
            onClick={() => reviewTarget
              && reviewMutation.mutate({ userId: reviewTarget.id, decision: 'APPROVE' })}
          >
            {t('approve')}
          </Button>,
        ]}
      >
        <p>{t('reviewDecision')}</p>
      </Modal>
    </div>
  );
}
