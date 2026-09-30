import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Tag } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { useInsightAudit } from '../hooks';
import type { InsightAuditItem, RiskLevel, TimeRange } from '../types';

const EVENT_LABELS: Record<string, string> = { CREATE: '创建', UPDATE: '更新', RETRY: '重试', REJECT: '驳回' };

const RISK_LABELS: Record<RiskLevel, { color: string; label: string }> = {
  high: { color: 'error', label: '高危' },
  medium: { color: 'warning', label: '中危' },
  low: { color: 'default', label: '低危' },
};

interface AuditTableProps {
  riskFilter: string;
  workerId?: number;
  workerName: string;
  timeRange: TimeRange;
}

export function AuditTable({ workerId, workerName, timeRange, riskFilter }: AuditTableProps) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = usePageSizePreference('autowonder.insights.audit.pageSize', [10, 20, 50, 100], 10);
  const { data, isLoading, isError, refetch } = useInsightAudit(page, pageSize, riskFilter, workerId, workerName, timeRange);

  useEffect(() => {
    setPage(1);
  }, [workerId, timeRange, riskFilter]);

  const columns: ColumnsType<InsightAuditItem> = [
    {
      title: '时间', dataIndex: 'timestamp', width: 130,
      render: (t: string) => <span style={{ whiteSpace: 'nowrap', fontSize: 12 }}>{t ? t.replace('T', ' ').slice(5, 16) : '-'}</span>,
    },
    {
      title: '操作人', dataIndex: 'worker', align: 'center', width: 180, ellipsis: { showTitle: false },
      render: (worker: string, record: InsightAuditItem) => {
        const isHuman = record.operatorType === 'HUMAN';
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6, maxWidth: '100%', verticalAlign: 'middle' }}>
            {isHuman
              ? <UserOutlined style={{ color: 'var(--aw-success)', fontSize: 12, flexShrink: 0 }} />
              : <span style={{ color: 'var(--aw-accent-text)', fontSize: 12, display: 'inline-flex', flexShrink: 0 }}><RobotHeadIcon /></span>}
            <EllipsisText tooltip={worker || '-'} style={{ fontSize: 12, minWidth: 0 }}>{worker || '-'}</EllipsisText>
          </span>
        );
      },
    },
    {
      title: '操作类型', dataIndex: 'eventType', align: 'left', width: 200, ellipsis: { showTitle: false },
      render: (t: string) => <Tag style={{ maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'middle' }}>{EVENT_LABELS[t] ?? t}</Tag>,
    },
    {
      title: '操作详情', dataIndex: 'detail', align: 'left', width: 280, ellipsis: { showTitle: false },
      render: (t: string) => {
        const match = t?.match(/(aone#\d+)\s*(.*)/);
        if (match) {
          return (
            <EllipsisText tooltip={t} style={{ fontSize: 12 }}>
              <span style={{ color: 'var(--aw-accent-text)', fontWeight: 500 }}>{match[1]}</span> {match[2]}
            </EllipsisText>
          );
        }
        return <EllipsisText tooltip={t} style={{ fontSize: 12 }}>{t}</EllipsisText>;
      },
    },
    {
      title: '风险', dataIndex: 'riskLevel', width: 70, align: 'center',
      render: (level: RiskLevel) => {
        const style = RISK_LABELS[level] || { color: 'default', label: '未知' };
        return <Tag color={style.color}>{style.label}</Tag>;
      },
    },
  ];

  if (isError) return <Alert type="error" showIcon message="执行审计加载失败" action={<Button onClick={() => { void refetch(); }}>重试</Button>} />;

  return (
    <div>
      <Table
        scroll={{ x: 800 }}
        dataSource={data?.items || []}
        columns={columns}
        rowKey={(record) => `${record.timestamp}-${record.worker}-${record.eventType}-${record.detail}`}
        loading={isLoading}
        pagination={{
          current: page,
          total: data?.total || 0,
          pageSize,
          onChange: (nextPage, nextSize) => {
            setPage(nextPage);
            if (nextSize !== pageSize) setPageSize(nextSize);
          },
          showSizeChanger: true,
          showTotal: total => `共 ${total.toLocaleString()} 条`,
        }}
        locale={{
          emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行审计记录" />,
        }}
      />
    </div>
  );
}
