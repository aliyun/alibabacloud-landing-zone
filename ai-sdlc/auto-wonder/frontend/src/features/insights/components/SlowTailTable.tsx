import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { Alert, Button, Empty } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import { useHumanAgentSlowTail } from '../hooks';
import { formatDurationCompact } from '@/shared/lib/duration';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import type { P90Workitem } from '../types';

const timeOptions: Intl.DateTimeFormatOptions = { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false };

interface SlowTailTableProps {
  startDate: string;
  endDate: string;
  workType: string;
}

function humanRatio(item: P90Workitem): number {
  const total = item.humanDurationSeconds + item.agentDurationSeconds;
  if (total === 0) return 0;
  return item.humanDurationSeconds / total;
}

export function SlowTailTable({ startDate, endDate, workType }: SlowTailTableProps) {
  const { t } = useTranslation('insights');
  const { data, isLoading, isError, refetch } = useHumanAgentSlowTail(startDate, endDate, workType);

  const columns: ColumnsType<P90Workitem> = [
    {
      title: t('slowTail.id'),
      dataIndex: 'workitemId',
      width: 96,
      render: (id: number) => <a href={`/workitems/${id}`} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a>,
    },
    {
      title: t('slowTail.title'),
      dataIndex: 'title', align: 'left',
      ellipsis: { showTitle: false },
      render: (title: string) => <EllipsisText>{title}</EllipsisText>,
    },
    {
      title: t('slowTail.completedAt'),
      dataIndex: 'completedAt',
      width: 144,
      render: (v: string) => <span>{formatDateTime(v, timeOptions)}</span>,
    },
    {
      title: t('slowTail.totalDuration'),
      dataIndex: 'totalDurationSeconds',
      width: 112,
      render: (v: number) => <span style={{ fontWeight: 500 }}>{formatDurationCompact(v)}</span>,
    },
    {
      title: t('slowTail.human'),
      dataIndex: 'humanDurationSeconds',
      width: 112,
      render: (v: number) => <span style={{ color: 'var(--insight-human)' }}>{formatDurationCompact(v)}</span>,
    },
    {
      title: t('slowTail.agent'),
      dataIndex: 'agentDurationSeconds',
      width: 112,
      render: (v: number) => <span style={{ color: 'var(--insight-agent)' }}>{formatDurationCompact(v)}</span>,
    },
    {
      title: t('slowTail.humanShare'),
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

  if (isError) return <Alert type="error" showIcon message={t('slowTail.loadFailure')} action={<Button onClick={() => { void refetch(); }}>{t('participation.retry')}</Button>} />;

  return (
    <Table<P90Workitem>
      className="participation-slow-tail-table"
      tableLayout="fixed"
      columns={columns}
      dataSource={data?.items ?? []}
      scroll={{ x: 1060 }}
      locale={{ emptyText: isLoading ? t('slowTail.loading') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('slowTail.empty')} /> }}
      rowKey="workitemId"
      size="small"
      loading={isLoading}
      pagination={false}
    />
  );
}
