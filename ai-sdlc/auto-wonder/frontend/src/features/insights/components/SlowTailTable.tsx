import { useState } from 'react';
import { Alert, Button, Empty } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useHumanAgentSlowTail } from '../hooks';
import { formatDurationCompact } from '@/shared/lib/duration';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import type { P90Workitem } from '../types';

interface SlowTailTableProps {
  startDate: string;
  endDate: string;
}

function humanRatio(item: P90Workitem): number {
  const total = item.humanDurationSeconds + item.agentDurationSeconds;
  if (total === 0) return 0;
  return item.humanDurationSeconds / total;
}

const columns: ColumnsType<P90Workitem> = [
  {
    title: '工单ID',
    dataIndex: 'workitemId',
    width: 96,
    render: (id: number) => <a href={`/workitems/${id}`} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a>,
  },
  {
    title: '标题',
    dataIndex: 'title', align: 'left',
    ellipsis: { showTitle: false },
    render: (title: string) => <EllipsisText>{title}</EllipsisText>,
  },
  {
    title: '完成时间',
    dataIndex: 'completedAt',
    width: 144,
    render: (v: string) => <span>{v && dayjs(v).isValid() ? dayjs(v).format('MM-DD HH:mm') : '—'}</span>,
  },
  {
    title: '总耗时',
    dataIndex: 'totalDurationSeconds',
    width: 112,
    render: (v: number) => <span style={{ fontWeight: 500 }}>{formatDurationCompact(v)}</span>,
  },
  {
    title: '人工负责',
    dataIndex: 'humanDurationSeconds',
    width: 112,
    render: (v: number) => <span style={{ color: 'var(--insight-human)' }}>{formatDurationCompact(v)}</span>,
  },
  {
    title: 'Agent 负责',
    dataIndex: 'agentDurationSeconds',
    width: 112,
    render: (v: number) => <span style={{ color: 'var(--insight-agent)' }}>{formatDurationCompact(v)}</span>,
  },
  {
    title: '人工负责时长占比',
    key: 'humanRatio',
    width: 180,
    render: (_: unknown, record: P90Workitem) => {
      if (record.humanDurationSeconds + record.agentDurationSeconds === 0) return '—';
      const ratio = humanRatio(record);
      const pct = Math.round(ratio * 100);
      return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, maxWidth: 148, marginInline: 'auto' }}>
          <div style={{ flex: 1, height: 6, background: 'var(--aw-border)', borderRadius: 3, overflow: 'hidden' }}>
            <div style={{ width: `${pct}%`, height: '100%', background: 'var(--insight-human)', borderRadius: 3 }} />
          </div>
          <span style={{ fontSize: 12, color: 'var(--aw-muted)', minWidth: 38, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{pct}%</span>
        </div>
      );
    },
  },
];

export function SlowTailTable({ startDate, endDate }: SlowTailTableProps) {
  const [page, setPage] = useState(1);
  const pageSize = 10;
  const { data, isLoading, isError, refetch } = useHumanAgentSlowTail(startDate, endDate, page, pageSize);

  if (isError) return <Alert type="error" showIcon message="慢尾工单加载失败" action={<Button onClick={() => { void refetch(); }}>重试</Button>} />;

  return (
    <Table<P90Workitem>
      className="participation-slow-tail-table"
      tableLayout="fixed"
      columns={columns}
      dataSource={data?.items ?? []}
      scroll={{ x: 1060 }}
      locale={{ emptyText: isLoading ? '正在加载慢尾工单…' : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无慢尾工单" /> }}
      rowKey="workitemId"
      size="small"
      loading={isLoading}
      pagination={{
        current: page,
        pageSize,
        total: data?.total ?? 0,
        onChange: (p) => setPage(p),
        showSizeChanger: false,
        size: 'small',
        showTotal: total => `共 ${total.toLocaleString()} 条工单`,
      }}
    />
  );
}
