import { useState, useRef, useCallback, useEffect } from 'react';
import { Card, Table, Tag, Select, Input, Segmented, Button, Space, Modal, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import { useDispatches } from './hooks';
import { listAgents } from '@/features/agent/api';
import { statusMeta, ACCENT } from './statusMeta';
import type { DispatchVO, DispatchTimeRange } from './types';
import { ExecutionDetailDrawer } from './components/ExecutionDetailDrawer';
import { MarkdownView } from '@/shared/ui/MarkdownView';

const STATUS_OPTIONS = [
  'PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED',
].map((s) => ({ label: s, value: s }));

export function ExecutionListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const [status, setStatus] = useState<string | undefined>();
  const [agentId, setAgentId] = useState<number | undefined>();
  const [workitemId, setWorkitemId] = useState<number | undefined>();
  const [timeRange, setTimeRange] = useState<DispatchTimeRange>('30d');
  const [detailId, setDetailId] = useState<number | null>(null);
  const [resultPreview, setResultPreview] = useState<string | null>(null);
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
    { title: '工单', dataIndex: 'workitemTitle', width: 220, ellipsis: true,
      render: (_: unknown, r) => r.workitemId
        ? <a onClick={(e) => { e.stopPropagation(); navigate(`/workitems/${r.workitemId}`); }}>#{r.workitemId} {r.workitemTitle}</a>
        : '—' },
    { title: 'Agent', dataIndex: 'agentName', width: 120, render: (v: string | null) => v ?? '—' },
    { title: '执行器', dataIndex: 'executorName', width: 120, render: (v: string | null) => v ?? '—' },
    { title: '状态', dataIndex: 'status', width: 130,
      render: (s: string) => <Tag color={statusMeta(s).color}>{statusMeta(s).label}</Tag> },
    { title: '尝试', dataIndex: 'attempt', width: 70, render: (v: number | null) => v ?? '—' },
    {
      title: '结果', dataIndex: 'resultSummary', width: 260, ellipsis: true,
      render: (v: string | null) => v ? (
        <Space size={8} style={{ maxWidth: 240 }}>
          <Typography.Text ellipsis style={{ maxWidth: 150 }}>{v}</Typography.Text>
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            onClick={(e) => {
              e.stopPropagation();
              setResultPreview(v);
            }}
          >
            查看完整结果
          </Button>
        </Space>
      ) : '—',
    },
    { title: '操作', width: 80, fixed: 'right' as const,
      render: (_: unknown, r) => <a onClick={(e) => { e.stopPropagation(); setDetailId(r.id); }}>详情</a> },
  ];

  return (
    <Card
      title={<span style={{ borderLeft: `4px solid ${ACCENT}`, paddingLeft: 10, fontWeight: 600 }}>执行记录</span>}
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
        scroll={{ x: 'max-content' }}
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
      <Modal
        title="完整结果"
        open={resultPreview != null}
        onCancel={() => setResultPreview(null)}
        footer={null}
        width={760}
      >
        {resultPreview ? <MarkdownView content={resultPreview} /> : null}
      </Modal>
    </Card>
  );
}
