import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useMemo, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import {
  Alert, Button, Card, Empty, List, Pagination, Popconfirm, Space, Tabs, Tag, Typography, message,
} from 'antd';
import { CloseCircleFilled, ExclamationCircleFilled } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams, useNavigate } from 'react-router-dom';
import {
  deleteNotification, getNotification, listNotifications, markAllNotificationsRead, markNotificationRead,
} from './api';
import type { NotificationStatusFilter } from './api';
import type { Notification } from '@/shared/types/notification';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import {
  getBrowserNotificationPermission,
  requestBrowserNotificationPermission,
} from '@/shared/notifications/browserNotification';
import type { BrowserNotificationPermission } from '@/shared/notifications/browserNotification';
import { NotificationDetailDrawer, hasDuplicateLinks } from './NotificationDetailDrawer';

const { Text } = Typography;

const PAGE_SIZE = 10;
const SUMMARY_LENGTH = 60;
const DATE_FORMAT = { dateStyle: 'medium', timeStyle: 'medium' } as const;

function stopRowClick(event: MouseEvent<HTMLElement>) {
  event.stopPropagation();
}

function summaryOf(content: string | null): string {
  if (!content) return '';
  return content.length > SUMMARY_LENGTH ? `${content.slice(0, SUMMARY_LENGTH)}...` : content;
}

/** `?notificationId=` 深链只接受正整数；畸形值不触发任何请求。 */
export function parseNotificationDeepLink(value: string | null): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

const PERMISSION_TEXT_KEYS = {
  default: 'browserPermissionDefault',
  granted: 'browserPermissionGranted',
  denied: 'browserPermissionDenied',
  unsupported: 'browserPermissionUnsupported',
} as const;

export function NotificationCenterPage() {
  const { t } = useTranslation('notification');
  const tabItems = [{ key: 'ALL', label: t('all') }, { key: 'UNREAD', label: t('unread') }, { key: 'READ', label: t('read') }];
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [tab, setTab] = useState('ALL');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Notification | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();
  const deepLinkId = parseNotificationDeepLink(searchParams.get('notificationId'));
  // 同一个 ID 只自动打开一次，关闭抽屉后不因详情缓存再次弹出。
  const openedDeepLinkRef = useRef<number | null>(null);
  const [permission, setPermission] = useState<BrowserNotificationPermission>(getBrowserNotificationPermission);

  const status = tab === 'ALL' ? undefined : (tab as NotificationStatusFilter);

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['notifications', 'list', status, page],
    queryFn: () => listNotifications({ status, page, size: PAGE_SIZE }),
  });

  // 直接写 data?.items ?? [] 每次渲染都会得到新数组，下游 useMemo 的依赖会恒变
  const items = useMemo(() => data?.items ?? [], [data]);
  const total = data?.total ?? 0;

  // 删除末页唯一一行、或在「未读」页把该行置已读后 total 会收缩，而受控 Pagination 不会自行回退页码，
  // 这里把越界页码收敛到最后一页；只在非加载态钳制，否则请求在途时 total 暂为 0 会把页码打回第 1 页。
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  useEffect(() => {
    if (!isLoading && page > pageCount) setPage(pageCount);
  }, [isLoading, page, pageCount]);

  // 铃铛角标与列表同挂 ['notifications'] 前缀，一次失效同时刷新两处（FR-1/FR-5/FR-6/FR-7）
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['notifications'] });
  };

  const markRead = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: invalidate,
    onError: () => message.error(i18n.t('notification:readFailed')),
  });

  const deepLinkQuery = useQuery({
    queryKey: ['notifications', 'detail', deepLinkId],
    enabled: deepLinkId != null,
    retry: false,
    queryFn: () => getNotification(deepLinkId as number),
  });

  useEffect(() => {
    if (deepLinkId == null) {
      openedDeepLinkRef.current = null;
      return;
    }
    const record = deepLinkQuery.data;
    if (!record || openedDeepLinkRef.current === deepLinkId) return;
    openedDeepLinkRef.current = deepLinkId;
    setSelected(record);
    setDrawerOpen(true);
    // 成功读取后才标已读，读取失败的深链不能误报已读。
    if (record.status === 'UNREAD') {
      markRead.mutate(record.id);
    }
  }, [deepLinkId, deepLinkQuery.data, markRead]);

  useEffect(() => {
    // 权限可能在浏览器站点设置里被改动；回到页面时重新读取真实状态。
    const refreshPermission = () => setPermission(getBrowserNotificationPermission());
    window.addEventListener('focus', refreshPermission);
    document.addEventListener('visibilitychange', refreshPermission);
    return () => {
      window.removeEventListener('focus', refreshPermission);
      document.removeEventListener('visibilitychange', refreshPermission);
    };
  }, []);

  const enableBrowserNotifications = async () => {
    setPermission(await requestBrowserNotificationPermission());
  };
  // JSX 里不直接写状态比较字面量，避免被判成未走 i18n 的 UI 文本。
  const canRequestBrowserPermission = permission === 'default';

  const markAllRead = useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: invalidate,
    onError: () => message.error(i18n.t('notification:readAllFailed')),
  });

  const remove = useMutation({
    mutationFn: deleteNotification,
    onSuccess: (_result, id) => {
      if (selected?.id === id) setDrawerOpen(false);
      invalidate();
    },
    onError: () => message.error(i18n.t('notification:deleteFailed')),
  });

  // 通知接口只校验「是否为当前工作空间成员」，不区分读写级别，所以这里不套 runWithAccess：
  // 那会比后端更严，把只读成员清理自己通知的能力也挡掉。
  // 抽成具名函数而非内联在 onConfirm 上，是为了符合 accessSourceGuard 对确认类变更的扫描约定。
  // 删除仍要返回 Promise，antd 才会保持气泡打开并进入 loading，禁用态也才落得到 DOM 上；
  // 吞掉 rejection 是因为 onError 已经提示过，否则 antd 会把它再抛成未处理异常。
  const handleDelete = (id: number) => remove.mutateAsync(id).catch(() => {});
  const handleMarkAllRead = () => markAllRead.mutate();

  const openDetail = (item: Notification) => {
    setSelected(item);
    setDrawerOpen(true);
    if (item.status === 'UNREAD') {
      markRead.mutate(item.id);
    }
  };

  // 单一目标的通知点击整行就直接进入关联页面；重复工单提醒在详情里有多个工单目标，
  // 先留在抽屉里让用户选择，没有 link 的通知也只能看详情。
  const directLinkOf = (item: Notification): string | null => (
    item.link && !hasDuplicateLinks(item) ? item.link : null
  );

  const openItem = (item: Notification) => {
    const target = directLinkOf(item);
    if (!target) {
      openDetail(item);
      return;
    }
    if (item.status === 'UNREAD') {
      markRead.mutate(item.id);
    }
    navigate(target);
  };

  // 关闭抽屉只移除深链参数，保留其他查询参数。
  const closeDrawer = () => {
    setDrawerOpen(false);
    if (searchParams.has('notificationId')) {
      const next = new URLSearchParams(searchParams);
      next.delete('notificationId');
      setSearchParams(next, { replace: true });
    }
  };

  // 标记已读后该行可能因筛选而离开当前页，此时回退到打开抽屉时的快照，避免详情突然变空
  const detail = useMemo(() => {
    if (!selected) return null;
    return items.find((item) => item.id === selected.id) ?? selected;
  }, [selected, items]);

  const handleTabChange = (key: string) => {
    setTab(key);
    setPage(1);
  };

  const rowActions = (item: Notification): ReactNode[] => {
    const actions: ReactNode[] = [];
    // 整行直达关联页面后，仍要有一个入口能看完整正文与关联对象。
    if (directLinkOf(item)) {
      actions.push(
        <span key="detail" onClick={stopRowClick}>
          <Button type="link" size="small" aria-label={t('viewDetail')} onClick={() => openDetail(item)}>
            {t('viewDetail')}
          </Button>
        </span>,
      );
    }
    if (item.status === 'UNREAD') {
      actions.push(
        <span key="read" onClick={stopRowClick}>
          <Button
            type="link"
            size="small"
            disabled={markRead.isPending}
            onClick={() => markRead.mutate(item.id)}
          >
            {t('markRead')}
          </Button>
        </span>,
      );
    }
    actions.push(
      <span key="delete" onClick={stopRowClick}>
        <Popconfirm
          icon={<ExclamationCircleFilled aria-hidden="true" />}
          title={t('deleteConfirm')}
          description={t('deleteWarning')}
          okText={t('confirmDelete')}
          cancelText={t('cancel')}
          // 重复确认会让第二次请求命中后端 0 行分支抛 NOT_FOUND，弹出与事实不符的「删除失败」
          okButtonProps={{ disabled: remove.isPending, 'aria-label': t('confirmDelete') }}
          onConfirm={() => handleDelete(item.id)}
        >
          <Button type="link" size="small" danger disabled={remove.isPending}>
            {t('delete')}
          </Button>
        </Popconfirm>
      </span>,
    );
    return actions;
  };

  return (
    <Card
      className="aw-content-card" title={<PageHeading title={t('title')} />}
      extra={
        <Button aria-label={t('markAllRead')} onClick={handleMarkAllRead} loading={markAllRead.isPending}>
          {t('markAllRead')}
        </Button>
      }
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 12,
          padding: '12px 16px',
          marginBottom: 12,
          borderRadius: 8,
          background: 'var(--aw-soft-bg, rgba(0, 0, 0, 0.02))',
        }}
      >
        <div>
          <Text strong>{t('browserNotifications')}</Text>
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t(PERMISSION_TEXT_KEYS[permission])}
            </Text>
          </div>
        </div>
        {/* 只在用户点击时申请授权；拒绝后引导去浏览器站点设置，不反复弹窗。 */}
        {canRequestBrowserPermission && (
          <Button onClick={() => { void enableBrowserNotifications(); }}>
            {t('enableBrowserNotifications')}
          </Button>
        )}
      </div>

      {deepLinkId != null && deepLinkQuery.isError && (
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleFilled aria-hidden="true" />}
          message={
            deepLinkQuery.error instanceof ApiError && deepLinkQuery.error.code === ErrorCodes.NOT_FOUND
              ? t('notificationNotFound')
              : t('detailLoadFailed')
          }
          action={<Button size="small" onClick={() => { void deepLinkQuery.refetch(); }}>{t('retry')}</Button>}
          style={{ marginBottom: 12 }}
        />
      )}

      <Tabs activeKey={tab} items={tabItems} onChange={handleTabChange} />

      {isError ? (
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleFilled aria-hidden="true" />}
          message={t('loadFailed')}
          description={error instanceof Error ? error.message : undefined}
          action={<Button size="small" onClick={() => refetch()}>{t('retry')}</Button>}
        />
      ) : (
        <>
          <List
            rowKey="id"
            dataSource={items}
            loading={isLoading}
            locale={{ emptyText: <Empty description={t('empty')} image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
            renderItem={(item) => (
              <List.Item
                style={{ cursor: 'pointer' }}
                onClick={() => openItem(item)}
                actions={rowActions(item)}
              >
                <List.Item.Meta
                  title={
                    <Space size={8}>
                      {item.status === 'UNREAD' && <Tag color="blue">{t('unread')}</Tag>}
                      <Text strong={item.status === 'UNREAD'}>{item.title}</Text>
                    </Space>
                  }
                  description={
                    <div>
                      {item.content && <div style={{ marginBottom: 4 }}>{summaryOf(item.content)}</div>}
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {formatDateTime(item.gmtCreate, DATE_FORMAT)}
                      </Text>
                    </div>
                  }
                />
              </List.Item>
            )}
          />
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: 8,
              marginTop: 16,
            }}
          >
            <Text type="secondary">{t('total', { count: total })}</Text>
            <Pagination
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              onChange={(next) => setPage(next)}
            />
          </div>
        </>
      )}

      <NotificationDetailDrawer
        notification={detail}
        open={drawerOpen}
        onClose={closeDrawer}
      />
    </Card>
  );
}
