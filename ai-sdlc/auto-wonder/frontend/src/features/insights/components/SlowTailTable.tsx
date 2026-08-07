import { useState } from 'react';
import { Table, Empty } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useHumanAgentSlowTail } from '../hooks';
import type { P90Workitem } from '../types';

interface SlowTailTableProps {
  startDate: string;
  endDate: string;
}

function formatSeconds(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
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
    width: 80,
    render: (id: number) => <span style={{ fontFamily: 'monospace' }}>#{id}</span>,
  },
  {
    title: '标题',
    dataIndex: 'title',
    ellipsis: true,
  },
  {
    title: '完成时间',
    dataIndex: 'completedAt',
    width: 120,
    render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
  },
  {
    title: '总耗时',
    dataIndex: 'totalDurationSeconds',
    width: 90,
    sorter: (a, b) => a.totalDurationSeconds - b.totalDurationSeconds,
    render: (v: number) => <span style={{ fontWeight: 500 }}>{formatSeconds(v)}</span>,
  },
  {
    title: '人工',
    dataIndex: 'humanDurationSeconds',
    width: 80,
    render: (v: number) => <span style={{ color: '#1890ff' }}>{formatSeconds(v)}</span>,
  },
  {
    title: 'Agent',
    dataIndex: 'agentDurationSeconds',
    width: 80,
    render: (v: number) => <span style={{ color: '#faad14' }}>{formatSeconds(v)}</span>,
  },
  {
    title: '人工占比',
    key: 'humanRatio',
    width: 140,
    render: (_: unknown, record: P90Workitem) => {
      const ratio = humanRatio(record);
      const pct = Math.round(ratio * 100);
      return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ flex: 1, height: 6, background: '#f0f0f0', borderRadius: 3, overflow: 'hidden' }}>
            <div style={{ width: `${pct}%`, height: '100%', background: '#1890ff', borderRadius: 3 }} />
          </div>
          <span style={{ fontSize: 11, color: '#8c8c8c', minWidth: 32 }}>{pct}%</span>
        </div>
      );
    },
  },
];

export function SlowTailTable({ startDate, endDate }: SlowTailTableProps) {
  const [page, setPage] = useState(1);
  const pageSize = 10;
  const { data, isLoading } = useHumanAgentSlowTail(startDate, endDate, page, pageSize);

  if (!data || data.total === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无慢尾工单" style={{ padding: 24 }} />;
  }

  return (
    <Table<P90Workitem>
      columns={columns}
      dataSource={data.items}
      rowKey="workitemId"
      size="small"
      loading={isLoading}
      pagination={{
        current: page,
        pageSize,
        total: data.total,
        onChange: (p) => setPage(p),
        showSizeChanger: false,
        size: 'small',
      }}
    />
  );
}
