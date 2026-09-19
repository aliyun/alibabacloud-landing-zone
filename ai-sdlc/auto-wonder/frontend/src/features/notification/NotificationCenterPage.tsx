import { useEffect, useMemo, useState, type MouseEvent, type ReactNode } from 'react';
import {
  Alert, Button, Card, Empty, List, Pagination, Popconfirm, Space, Tabs, Tag, Typography, message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  deleteNotification, listNotifications, markAllNotificationsRead, markNotificationRead,
} from './api';
import type { NotificationStatusFilter } from './api';
import type { Notification } from '@/shared/types/notification';
import { NotificationDetailDrawer } from './NotificationDetailDrawer';

const { Text } = Typography;

const PAGE_SIZE = 10;
const SUMMARY_LENGTH = 60;

const TAB_ITEMS = [
  { key: 'ALL', label: '全部' },
  { key: 'UNREAD', label: '未读' },
  { key: 'READ', label: '已读' },
];

function stopRowClick(event: MouseEvent<HTMLElement>) {
  event.stopPropagation();
}

function summaryOf(content: string | null): string {
  if (!content) return '';
  return content.length > SUMMARY_LENGTH ? `${content.slice(0, SUMMARY_LENGTH)}...` : content;
}

export function NotificationCenterPage() {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('ALL');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Notification | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

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
    onError: () => message.error('标记已读失败'),
  });

  const markAllRead = useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: invalidate,
    onError: () => message.error('全部已读失败'),
  });

  const remove = useMutation({
    mutationFn: deleteNotification,
    onSuccess: (_result, id) => {
      if (selected?.id === id) setDrawerOpen(false);
      invalidate();
    },
    onError: () => message.error('删除失败'),
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
    if (item.status === 'UNREAD') {
      actions.push(
        <span key="read" onClick={stopRowClick}>
          <Button
            type="link"
            size="small"
            disabled={markRead.isPending}
            onClick={() => markRead.mutate(item.id)}
          >
            标记已读
          </Button>
        </span>,
      );
    }
    actions.push(
      <span key="delete" onClick={stopRowClick}>
        <Popconfirm
          title="确认删除这条通知？"
          description="删除后不可恢复"
          okText="确认删除"
          cancelText="取消"
          // 重复确认会让第二次请求命中后端 0 行分支抛 NOT_FOUND，弹出与事实不符的「删除失败」
          okButtonProps={{ disabled: remove.isPending }}
          onConfirm={() => handleDelete(item.id)}
        >
          <Button type="link" size="small" danger disabled={remove.isPending}>
            删除
          </Button>
        </Popconfirm>
      </span>,
    );
    return actions;
  };

  return (
    <Card
      title="通知中心"
      extra={
        <Button onClick={handleMarkAllRead} loading={markAllRead.isPending}>
          全部已读
        </Button>
      }
    >
      <Tabs activeKey={tab} items={TAB_ITEMS} onChange={handleTabChange} />

      {isError ? (
        <Alert
          type="error"
          showIcon
          message="通知列表加载失败"
          description={error instanceof Error ? error.message : undefined}
          action={<Button size="small" onClick={() => refetch()}>重试</Button>}
        />
      ) : (
        <>
          <List
            rowKey="id"
            dataSource={items}
            loading={isLoading}
            locale={{ emptyText: <Empty description="暂无通知" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
            renderItem={(item) => (
              <List.Item
                style={{ cursor: 'pointer' }}
                onClick={() => openDetail(item)}
                actions={rowActions(item)}
              >
                <List.Item.Meta
                  title={
                    <Space size={8}>
                      {item.status === 'UNREAD' && <Tag color="blue">未读</Tag>}
                      <Text strong={item.status === 'UNREAD'}>{item.title}</Text>
                    </Space>
                  }
                  description={
                    <div>
                      {item.content && <div style={{ marginBottom: 4 }}>{summaryOf(item.content)}</div>}
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {new Date(item.gmtCreate).toLocaleString('zh-CN')}
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
            <Text type="secondary">{`共 ${total} 条`}</Text>
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
        onClose={() => setDrawerOpen(false)}
      />
    </Card>
  );
}
