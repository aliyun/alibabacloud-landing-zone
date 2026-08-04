import { useMemo, useState } from 'react';
import { Table, Tag, Segmented, Empty, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { ColumnsType } from 'antd/es/table';
import { workTypeMap, priorityMap, classifyWorkitemStatus } from '@/features/workitem/constants';
import type { Workitem } from '@/shared/types/workitem';

interface AgentWorkitemListProps {
  workitems: Workitem[];
  loading?: boolean;
}

const FILTERS = [
  { key: 'ALL', label: '全部' },
  { key: 'IN_PROGRESS', label: '执行中' },
  { key: 'PENDING_DECISION', label: '待决策' },
  { key: 'DONE', label: '已完成' },
];

export function AgentWorkitemList({ workitems, loading }: AgentWorkitemListProps) {
  const [filter, setFilter] = useState('ALL');

  const counts = useMemo(() => {
    const c: Record<string, number> = { ALL: workitems.length, IN_PROGRESS: 0, PENDING_DECISION: 0, DONE: 0 };
    workitems.forEach(w => {
      const k = classifyWorkitemStatus(w);
      if (k in c) c[k] += 1;
    });
    return c;
  }, [workitems]);

  const filtered = useMemo(
    () => (filter === 'ALL' ? workitems : workitems.filter(w => classifyWorkitemStatus(w) === filter)),
    [workitems, filter],
  );

  const columns: ColumnsType<Workitem> = [
    {
      title: '类型', dataIndex: 'workType', width: 80,
      render: (t: string) => {
        const m = workTypeMap[t] || { color: 'default', label: t };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    {
      title: '标题', dataIndex: 'title', ellipsis: true,
      render: (title: string, r: Workitem) => <Link to={`/workitems/${r.id}`}>{title}</Link>,
    },
    { title: '状态', dataIndex: 'statusName', width: 120, render: (s: string | null) => s || '-' },
    {
      title: '优先级', dataIndex: 'priority', width: 80,
      render: (p: number) => {
        const m = priorityMap[p] || priorityMap[3];
        return <Typography.Text style={{ color: m.color }}>{m.label}</Typography.Text>;
      },
    },
    { title: 'SDLC', dataIndex: 'sdlcName', width: 140, render: (n: string | null) => n || '-' },
    {
      title: '更新时间', dataIndex: 'gmtModified', width: 160,
      render: (t: string) => (t ? new Date(t).toLocaleString('zh-CN') : '-'),
    },
  ];

  return (
    <div>
      <Segmented
        style={{ marginBottom: 12 }}
        value={filter}
        onChange={(v) => setFilter(v as string)}
        options={FILTERS.map(f => ({ value: f.key, label: `${f.label} ${counts[f.key] ?? 0}` }))}
      />
      <Table
        rowKey={(r) => String(r.id)}
        columns={columns}
        dataSource={filtered}
        loading={loading}
        pagination={false}
        locale={{ emptyText: <Empty description="该员工暂无关联工单" /> }}
      />
    </div>
  );
}
