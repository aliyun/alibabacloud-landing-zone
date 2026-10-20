import { useState } from 'react';
import { Button, Card, Empty, Input, Popconfirm, Select, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { formatDateTime } from '@/shared/i18n/format';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { PageHeading } from '@/shared/ui/PageHeading';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import type { Workitem } from '@/shared/types/workitem';
import { workTypeMap } from './constants';
import { useRecycledWorkitemList, useRestoreWorkitem } from './hooks';

const PAGE_SIZE = 20;

const formatDeletedAt = (deletedAt: string | null | undefined) => (deletedAt
  ? formatDateTime(deletedAt, {
    year: 'numeric', month: 'numeric', day: 'numeric',
    hour: 'numeric', minute: 'numeric', second: 'numeric', hour12: false,
  })
  : null);

/** 工单回收站：只展示软删除工单；恢复仅还原可见性，不自动恢复执行。 */
export function WorkitemRecycleBinPage() {
  const { t } = useTranslation('workitem');
  const accessCommand = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(PAGE_SIZE);
  const [workType, setWorkType] = useState<string | undefined>();
  const [keyword, setKeyword] = useState<string | undefined>();

  const { data, isLoading } = useRecycledWorkitemList({ page, size, workType, keyword });
  const items = data?.list ?? [];
  const total = data?.total ?? 0;
  const restoreMutation = useRestoreWorkitem();

  const columns: ColumnsType<Workitem> = [
    { title: t('recycleBin.id'), dataIndex: 'id', width: 90 },
    {
      title: t('fields.title'),
      dataIndex: 'title',
      ellipsis: { showTitle: false },
      render: (title: string) => <EllipsisText>{title}</EllipsisText>,
    },
    {
      title: t('fields.type'),
      dataIndex: 'workType',
      width: 100,
      render: (type: string) => <Tag color={workTypeMap[type]?.color}>{workTypeMap[type]?.label || type}</Tag>,
    },
    {
      title: t('recycleBin.previousStatus'),
      dataIndex: 'statusName',
      width: 140,
      render: (statusName: string | null) => (statusName ? <Tag>{statusName}</Tag> : t('recycleBin.notAvailable')),
    },
    {
      title: t('fields.creator'),
      dataIndex: 'creatorDisplayName',
      width: 150,
      ellipsis: { showTitle: false },
      render: (creatorDisplayName: string | null, record: Workitem) => (
        <EllipsisText>{creatorDisplayName || record.creatorName || t('recycleBin.notAvailable')}</EllipsisText>
      ),
    },
    {
      title: t('recycleBin.deletedAt'),
      dataIndex: 'deletedAt',
      width: 190,
      render: (deletedAt: string | null) => formatDeletedAt(deletedAt) || t('recycleBin.unknownDeletedAt'),
    },
    {
      title: t('fields.actions'),
      key: 'actions',
      width: 110,
      render: (_: unknown, record: Workitem) => (
        <Popconfirm
          title={t('recycleBin.restoreConfirmTitle', { title: record.title })}
          description={t('recycleBin.restoreConfirmDescription')}
          okText={t('recycleBin.restore')}
          cancelText={t('list.cancel')}
          onConfirm={() => accessCommand(
            'READ_WRITE',
            t('recycleBin.restoreCommand'),
            () => restoreMutation.mutate({ id: record.id }),
          )}
        >
          <Button
            size="small"
            data-testid={`restore-workitem-${record.id}`}
            aria-label={t('recycleBin.restoreCommand')}
            loading={restoreMutation.isPending}
          >{t('recycleBin.restore')}</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <Card className="aw-content-card aw-workitems" title={<PageHeading title={t('recycleBin.title')} description={t('recycleBin.scope')} />}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          placeholder={t('list.typeFilter')}
          value={workType}
          allowClear
          style={{ width: 140 }}
          onChange={(value) => { setWorkType(value); setPage(1); }}
          options={Object.entries(workTypeMap).map(([value, meta]) => ({ value, label: meta.label }))}
        />
        <Input.Search
          allowClear
          placeholder={t('list.search')}
          onSearch={(value) => { setKeyword(value.trim() || undefined); setPage(1); }}
          style={{ width: 240 }}
        />
      </Space>

      {isLoading ? (
        <Table<Workitem> rowKey="id" loading columns={columns} dataSource={[]} pagination={false} />
      ) : items.length === 0 ? (
        <div data-testid="recycle-bin-empty">
          <Empty description={keyword
            ? t('recycleBin.noMatches', { keyword })
            : t('recycleBin.empty')}
          />
        </div>
      ) : (
        <Table<Workitem>
          rowKey="id"
          columns={columns}
          dataSource={items}
          pagination={{
            current: page,
            pageSize: size,
            total,
            showSizeChanger: true,
            onChange: (nextPage, nextSize) => { setPage(nextPage); setSize(nextSize); },
            showTotal: (count) => t('list.pagination_other', { count }),
          }}
        />
      )}
    </Card>
  );
}
