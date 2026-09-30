import { PageHeading } from '@/shared/ui/PageHeading';
import { useState, useRef, useCallback, useEffect } from 'react';
import { Card, Tag, Select, Input, Segmented, Button, Space } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import { useDispatches } from './hooks';
import { listAgents } from '@/features/agent/api';
import { statusMeta } from './statusMeta';
import type { DispatchVO, DispatchTimeRange } from './types';
import { ExecutionDetailDrawer } from './components/ExecutionDetailDrawer';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';

const STATUS_OPTIONS = [
  'PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED',
].map((s) => ({ label: s, value: s }));

export function ExecutionListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = usePageSizePreference('autowonder.executions.pageSize', [10, 20, 50, 100, 200], 10);
  const [status, setStatus] = useState<string | undefined>();
  const [agentId, setAgentId] = useState<number | undefined>();
  const [workitemId, setWorkitemId] = useState<number | undefined>();
  const [timeRange, setTimeRange] = useState<DispatchTimeRange>('30d');
  const [detailId, setDetailId] = useState<number | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();
  const handleWorkitemIdChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    clearTimeout(debounceRef.current);
    const raw = e.target.value;
    debounceRef.current = setTimeout(() => {
      const n = Number(raw);
      setWorkitemId(raw && !Number.isNaN(n) ? n : undefined);
      setPage(1);
    }, 300);
  }, []);
  useEffect(() => () => clearTimeout(debounceRef.current), []);

  const { data, isFetching, refetch } = useDispatches({ page, pageSize, status, agentId, workitemId, timeRange });
  const { data: agents } = useQuery({ queryKey: ['agents-for-filter'], queryFn: () => listAgents({ page: 1, size: 200 }) });

  const columns: ColumnsType<DispatchVO> = [
    { title: '时间', dataIndex: 'gmtCreate', width: 160,
      render: (v: string) => new Date(v).toLocaleString('zh-CN', { hour12: false }) },
    { title: '工单', align: 'left', dataIndex: 'workitemTitle', ellipsis: { showTitle: false },
      render: (_: unknown, r) => r.workitemId && r.workitemTitle
        ? (
          <EllipsisText tooltip={r.workitemTitle}>
            <a onClick={(e) => { e.stopPropagation(); navigate(`/workitems/${r.workitemId}`); }}>{r.workitemTitle}</a>
          </EllipsisText>
        )
        : '—' },
    { title: 'Agent', dataIndex: 'agentName', width: 120, render: (v: string | null) => v ?? '—' },
    { title: '执行器', dataIndex: 'executorName', width: 120, render: (v: string | null) => v ?? '—' },
    { title: '状态', dataIndex: 'status', width: 130,
      render: (s: string) => <Tag color={statusMeta(s).color}>{statusMeta(s).label}</Tag> },
    { title: '尝试', dataIndex: 'attempt', width: 70, render: (v: number | null) => v ?? '—' },
    { title: '操作', width: 80,
      render: (_: unknown, r) => <a onClick={(e) => { e.stopPropagation(); setDetailId(r.id); }}>详情</a> },
  ];

  return (
    <Card
      className="aw-content-card" title={<PageHeading title="执行记录" />}
      extra={<Button icon={<ReloadOutlined />} onClick={() => refetch()}>刷新</Button>}
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <Select allowClear placeholder="全部 Agent" style={{ width: 160 }} value={agentId}
          onChange={(v) => { setAgentId(v); setPage(1); }}
          options={(agents ?? []).map((a) => ({ label: a.name, value: a.id }))} />
        <Select allowClear placeholder="全部状态" style={{ width: 150 }} value={status}
          onChange={(v) => { setStatus(v); setPage(1); }} options={STATUS_OPTIONS} />
        <Input allowClear placeholder="工单 ID" style={{ width: 140 }}
          onChange={handleWorkitemIdChange} />
        <Segmented value={timeRange} onChange={(v) => { setTimeRange(v as DispatchTimeRange); setPage(1); }}
          options={[{ label: '近 7 天', value: '7d' }, { label: '近 30 天', value: '30d' }, { label: '近 90 天', value: '90d' }]} />
      </Space>

      <Table<DispatchVO>
        rowKey="id"
        loading={isFetching}
        columns={columns}
        dataSource={data?.list ?? []}
        onRow={(r) => ({ onClick: () => setDetailId(r.id) })}
        pagination={{
          current: page,
          pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
      />

      <ExecutionDetailDrawer dispatchId={detailId} open={detailId != null} onClose={() => setDetailId(null)} />
    </Card>
  );
}
