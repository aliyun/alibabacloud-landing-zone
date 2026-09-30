import { useMemo, useState } from 'react';
import { Tag, Segmented, Empty } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { listWorkitems } from '@/features/workitem/api';
import { workTypeMap, getPriorityMeta } from '@/features/workitem/constants';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import type { Workitem } from '@/shared/types/workitem';

interface AgentWorkitemListProps {
  agentId: number;
}

type FilterKey = 'ALL' | 'IN_PROGRESS' | 'PENDING_DECISION' | 'DONE';

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'IN_PROGRESS', label: '执行中' },
  { key: 'PENDING_DECISION', label: '待决策' },
  { key: 'DONE', label: '已完成' },
];

const PAGE_SIZE_STORAGE_KEY = 'autowonder.agentDetail.workitems.pageSize';

/** 服务端分页查询该数字员工名下的工单（与工单列表页同一接口），筛选也下推到服务端。 */
function useAgentWorkitemPage(agentId: number, page: number, size: number, filter: FilterKey) {
  return useQuery({
    queryKey: ['agent', agentId, 'workitems', page, size, filter],
    queryFn: () => listWorkitems({
      page,
      size,
      assigneeType: 'AGENT',
      assigneeRef: agentId,
      statusCategory: filter === 'ALL' ? undefined : filter,
    }),
    enabled: agentId > 0,
  });
}

/** 各筛选项的总数：size=1 只取 total，与看板每列独立查询同一模式。 */
function useAgentWorkitemCounts(agentId: number) {
  const all = useQuery({
    queryKey: ['agent', agentId, 'workitems', 'count', 'ALL'],
    queryFn: () => listWorkitems({ page: 1, size: 1, assigneeType: 'AGENT', assigneeRef: agentId }),
    enabled: agentId > 0,
  });
  const inProgress = useQuery({
    queryKey: ['agent', agentId, 'workitems', 'count', 'IN_PROGRESS'],
    queryFn: () => listWorkitems({ page: 1, size: 1, assigneeType: 'AGENT', assigneeRef: agentId, statusCategory: 'IN_PROGRESS' }),
    enabled: agentId > 0,
  });
  const pending = useQuery({
    queryKey: ['agent', agentId, 'workitems', 'count', 'PENDING_DECISION'],
    queryFn: () => listWorkitems({ page: 1, size: 1, assigneeType: 'AGENT', assigneeRef: agentId, statusCategory: 'PENDING_DECISION' }),
    enabled: agentId > 0,
  });
  const done = useQuery({
    queryKey: ['agent', agentId, 'workitems', 'count', 'DONE'],
    queryFn: () => listWorkitems({ page: 1, size: 1, assigneeType: 'AGENT', assigneeRef: agentId, statusCategory: 'DONE' }),
    enabled: agentId > 0,
  });
  return {
    ALL: all.data?.total ?? 0,
    IN_PROGRESS: inProgress.data?.total ?? 0,
    PENDING_DECISION: pending.data?.total ?? 0,
    DONE: done.data?.total ?? 0,
  };
}

export function AgentWorkitemList({ agentId }: AgentWorkitemListProps) {
  const [filter, setFilter] = useState<FilterKey>('ALL');
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference(PAGE_SIZE_STORAGE_KEY, [10, 20, 50, 100], 10);
  const counts = useAgentWorkitemCounts(agentId);
  const { data, isLoading } = useAgentWorkitemPage(agentId, page, size, filter);
  const workitems = useMemo(() => data?.list ?? [], [data]);
  const total = data?.total ?? 0;

  const columns: ColumnsType<Workitem> = [
    {
      title: '类型', dataIndex: 'workType', width: 80,
      render: (t: string) => {
        const m = workTypeMap[t] || { color: 'default', label: t };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    {
      title: '标题', dataIndex: 'title', align: 'left', ellipsis: { showTitle: false },
      render: (title: string, r: Workitem) => (
        <EllipsisText tooltip={title}>
          <Link to={`/workitems/${r.id}`}>{title}</Link>
        </EllipsisText>
      ),
    },
    { title: '状态', dataIndex: 'statusName', width: 120, render: (s: string | null) => s || '-' },
    {
      title: '优先级', dataIndex: 'priority', width: 90,
      render: (p: number) => {
        const m = getPriorityMeta(p);
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    {
      title: 'SDLC', dataIndex: 'sdlcName', width: 140, ellipsis: { showTitle: false },
      render: (n: string | null) => <EllipsisText tooltip={n ?? undefined}>{n || '-'}</EllipsisText>,
    },
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
        onChange={(v) => { setFilter(v as FilterKey); setPage(1); }}
        options={FILTERS.map(f => ({ value: f.key, label: `${f.label} ${counts[f.key]}` }))}
      />
      <Table
        rowKey={(r) => String(r.id)}
        columns={columns}
        dataSource={workitems}
        loading={isLoading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          onChange: (nextPage, nextSize) => {
            setPage(nextPage);
            if (nextSize !== size) setSize(nextSize);
          },
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
        }}
        locale={{ emptyText: <Empty description="该员工暂无关联工单" /> }}
      />
    </div>
  );
}
