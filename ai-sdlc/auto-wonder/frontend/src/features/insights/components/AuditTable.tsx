import { useEffect, useState } from 'react';
import { Empty, Table, Segmented, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useInsightAudit } from '../hooks';
import type { InsightAuditItem, RiskLevel, TimeRange } from '../types';

const RISK_LABELS: Record<RiskLevel, { bg: string; color: string; label: string }> = {
  high: { bg: 'rgba(220, 38, 38, 0.08)', color: '#dc2626', label: '高危' },
  medium: { bg: 'rgba(217, 119, 6, 0.08)', color: '#d97706', label: '中危' },
  low: { bg: 'rgba(107, 114, 128, 0.08)', color: '#6b7280', label: '低危' },
};

interface AuditTableProps {
  workerId?: number;
  workerName: string;
  timeRange: TimeRange;
}

export function AuditTable({ workerId, workerName, timeRange }: AuditTableProps) {
  const [page, setPage] = useState(1);
  const [riskFilter, setRiskFilter] = useState('');
  const { data, isLoading } = useInsightAudit(page, 50, riskFilter, workerId, workerName, timeRange);

  useEffect(() => {
    setPage(1);
  }, [workerId, timeRange]);

  const columns: ColumnsType<InsightAuditItem> = [
    {
      title: '时间', dataIndex: 'timestamp', width: 100,
      render: (t: string) => <span style={{ whiteSpace: 'nowrap', fontSize: 12 }}>{t ? t.replace('T', ' ').slice(5, 16) : '-'}</span>,
    },
    { title: '数字员工', dataIndex: 'worker', width: 130 },
    {
      title: '操作类型', dataIndex: 'eventType', width: 150,
      render: (t: string) => <Tag style={{ margin: 0, fontSize: 11 }}>{t}</Tag>,
    },
    {
      title: '关联工单', dataIndex: 'detail', width: 280,
      render: (t: string) => {
        const match = t?.match(/(aone#\d+)\s*(.*)/);
        if (match) {
          return <span title={t} style={{ fontSize: 12 }}><span style={{ color: '#f97316', fontWeight: 500 }}>{match[1]}</span> {match[2]}</span>;
        }
        return <span title={t} style={{ fontSize: 12 }}>{t}</span>;
      },
    },
    {
      title: '风险', dataIndex: 'riskLevel', width: 70, align: 'center',
      render: (level: RiskLevel) => {
        const style = RISK_LABELS[level] || RISK_LABELS.low;
        return <span style={{ fontSize: 11, padding: '2px 7px', borderRadius: 4, background: style.bg, color: style.color, fontWeight: 500 }}>{style.label}</span>;
      },
    },
  ];

  return (
    <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, padding: '18px 20px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
        <span style={{ fontSize: 14, fontWeight: 600, color: '#374151' }}>执行审计</span>
        <Segmented
          options={[
            { label: '全部', value: '' },
            { label: '高危', value: 'high' },
            { label: '中危', value: 'medium' },
            { label: '低危', value: 'low' },
          ]}
          value={riskFilter}
          onChange={(v) => { setRiskFilter(v as string); setPage(1); }}
        />
      </div>
      <Table
        dataSource={data?.items || []}
        columns={columns}
        rowKey={(record) => `${record.timestamp}-${record.worker}-${record.eventType}-${record.detail}`}
        size="small"
        loading={isLoading}
        pagination={{
          current: page,
          total: data?.total || 0,
          pageSize: 50,
          onChange: setPage,
          showSizeChanger: false,
        }}
        locale={{
          emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行审计记录" />,
        }}
      />
    </div>
  );
}
