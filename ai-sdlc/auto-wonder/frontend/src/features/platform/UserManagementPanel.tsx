import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Alert, Button, Input, Select, Space, Tag, Typography } from 'antd';
import { CloseCircleFilled, InfoCircleFilled, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Table } from '@/shared/theme/ThemedTable';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { formatDateTime } from '@/shared/i18n/format';
import {
  USER_ADMIN_LIST_QUERY_KEY,
  getUserAdminList,
  userAdminListQueryKey,
  type UserAdminListParams,
  type UserAdminStatus,
  type UserAdminStatusFilter,
  type UserAdminTypeFilter,
  type UserAdminUser,
} from './userAdminApi';
import { UserDetailDrawer, type UserDetailInitialAction } from './UserDetailDrawer';

const { Text } = Typography;

const EMPTY_TEXT = '—';
const PAGE_SIZE = 20;

const statusKeys = {
  NORMAL: { labelKey: 'statusNormal', color: 'success' },
  DELETED: { labelKey: 'statusDeleted', color: 'default' },
  DEACTIVATED: { labelKey: 'statusDeactivated', color: 'orange' },
} as const;

export function UserManagementPanel() {
  const { t } = useTranslation('userManagement');
  const queryClient = useQueryClient();
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<UserAdminStatusFilter>('ALL');
  const [typeFilter, setTypeFilter] = useState<UserAdminTypeFilter>('ALL');
  const [page, setPage] = useState(1);
  const [drawer, setDrawer] = useState<{ userId: number; initialAction?: UserDetailInitialAction } | null>(null);

  const params: UserAdminListParams = {
    keyword,
    status: statusFilter,
    type: typeFilter,
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
      width: '15%',
      render: (value: string) => <EllipsisText tooltip={value || undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colNickname'),
      dataIndex: 'nickname',
      width: '13%',
      render: (value: string | null) => <EllipsisText tooltip={value ?? undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colEmail'),
      dataIndex: 'email',
      width: '17%',
      render: (value: string | null) => <EllipsisText tooltip={value ?? undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colPhone'),
      dataIndex: 'phone',
      width: '12%',
      render: (value: string | null) => <EllipsisText tooltip={value ?? undefined}>{value || EMPTY_TEXT}</EllipsisText>,
    },
    {
      title: t('colStatus'),
      key: 'status',
      width: '9%',
      render: (_, record) => renderStatus(record.status),
    },
    {
      title: t('colType'),
      key: 'type',
      width: '10%',
      render: (_, record) => (
        record.isAdmin ? <Tag color="blue">{t('typeAdmin')}</Tag> : <Text type="secondary">{t('typeUser')}</Text>
      ),
    },
    {
      title: t('colCreatedAt'),
      dataIndex: 'createdAt',
      width: '12%',
      render: (value: string | null) => renderDateTime(value),
    },
    {
      title: t('colLastLoginAt'),
      dataIndex: 'lastLoginAt',
      width: '12%',
      render: (value: string | null) => renderDateTime(value),
    },
    {
      title: t('colActions'),
      key: 'actions',
      width: '15%',
      fixed: 'right',
      render: (_, record) => (
        <Space size={4} wrap>
          <Button type="link" size="small" aria-label={t('detail')} onClick={() => openDrawer(record.id)}>
            {t('detail')}
          </Button>
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
      ),
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
          style={{ width: 150 }}
          onChange={(value) => {
            setStatusFilter(value);
            setPage(1);
          }}
          options={[
            { value: 'ALL', label: t('statusAll') },
            { value: 'NORMAL', label: t('statusNormal') },
            { value: 'DELETED', label: t('statusDeleted') },
            { value: 'DEACTIVATED', label: t('statusDeactivated') },
          ]}
        />
        <Select<UserAdminTypeFilter>
          aria-label={t('typeFilter')}
          value={typeFilter}
          style={{ width: 150 }}
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
        scroll={{ x: 1240 }}
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
    </div>
  );
}
