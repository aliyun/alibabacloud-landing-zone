import { formatNumber } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
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

interface AuditTableProps {
  riskFilter: string;
  workerId?: number;
  workerName: string;
  timeRange: TimeRange;
}

export function AuditTable({ workerId, workerName, timeRange, riskFilter }: AuditTableProps) {
  const { t } = useTranslation('insights');
  const eventLabels = { CREATE: t('audit.events.CREATE'), UPDATE: t('audit.events.UPDATE'), RETRY: t('audit.events.RETRY'), REJECT: t('audit.events.REJECT') };
  const riskLabels = { high: { color: 'error', label: t('analytics.high') }, medium: { color: 'warning', label: t('analytics.medium') }, low: { color: 'default', label: t('analytics.low') } };
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = usePageSizePreference('autowonder.insights.audit.pageSize', [10, 20, 50, 100], 10);
  const { data, isLoading, isError, refetch } = useInsightAudit(page, pageSize, riskFilter, workerId, workerName, timeRange);

  useEffect(() => {
    setPage(1);
  }, [workerId, timeRange, riskFilter]);

  const columns: ColumnsType<InsightAuditItem> = [
    {
      title: t('audit.time'), dataIndex: 'timestamp', width: 130,
      render: (t: string) => <span style={{ whiteSpace: 'nowrap', fontSize: 12 }}>{t ? t.replace('T', ' ').slice(5, 16) : '-'}</span>,
    },
    {
      title: t('audit.operator'), dataIndex: 'worker', align: 'center', width: 180, ellipsis: { showTitle: false },
      render: (worker: string, record: InsightAuditItem) => {
        const isHuman = record.operatorType === 'HUMAN';
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6, maxWidth: '100%', verticalAlign: 'middle' }}>
            {isHuman
              ? <UserOutlined aria-hidden="true" style={{ color: 'var(--aw-success)', fontSize: 12, flexShrink: 0 }} />
              : <span style={{ color: 'var(--aw-accent-text)', fontSize: 12, display: 'inline-flex', flexShrink: 0 }}><RobotHeadIcon aria-hidden="true" /></span>}
            <EllipsisText tooltip={worker || '-'} style={{ fontSize: 12, minWidth: 0 }}>{worker || '-'}</EllipsisText>
          </span>
        );
      },
    },
    {
      title: t('audit.event'), dataIndex: 'eventType', align: 'left', width: 200, ellipsis: { showTitle: false },
      render: (t: string) => <Tag style={{ maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'middle' }}>{Object.prototype.hasOwnProperty.call(eventLabels, t) ? eventLabels[t as keyof typeof eventLabels] : t}</Tag>,
    },
    {
      title: t('audit.detail'), dataIndex: 'detail', align: 'left', width: 280, ellipsis: { showTitle: false },
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
      title: t('audit.risk'), dataIndex: 'riskLevel', width: 70, align: 'center',
      render: (level: RiskLevel) => {
        const style = Object.prototype.hasOwnProperty.call(riskLabels, level) ? riskLabels[level] : { color: 'default', label: level };
        return <Tag color={style.color}>{style.label}</Tag>;
      },
    },
  ];

  if (isError) return <Alert type="error" showIcon message={t('audit.loadFailure')} action={<Button onClick={() => { void refetch(); }}>{t('audit.retry')}</Button>} />;

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
          showTotal: total => t('audit.total', { count: total, total: formatNumber(total) }),
        }}
        locale={{
          emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={isLoading ? t('audit.loading') : t('audit.empty')} />,
        }}
      />
    </div>
  );
}
