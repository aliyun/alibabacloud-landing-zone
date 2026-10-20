import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Card, Segmented, Space, Statistic, Tag, Tooltip, Typography } from 'antd';
import { CloseCircleFilled, InfoCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { Table } from '@/shared/theme/ThemedTable';
import { PageHeading } from '@/shared/ui/PageHeading';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { getPriorityMeta } from '@/features/workitem/constants';
import { Sparkline } from '@/features/insights/components/Sparkline';
import { useCreatedWorkitems, useDeliveryOverview, usePendingWorkitems } from './hooks';
import type { CreatedWorkitem, PendingWorkitem } from './types';
import './DeliveryOverviewPage.css';

const DATE_TIME_OPTIONS = { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false } as const;
/** 与后端 daysInRange / 上一等长周期口径一致：含首尾。 */
const RANGE_DAYS = { week: 7, month: 30 } as const;
const TIMEZONE = 'Asia/Shanghai';
/** 「标题」列显式宽度：无 width 的列在固定表格布局下会被其余定宽列挤成 0 宽、标题不可见（人工反馈 141916）。 */
const TITLE_COLUMN_WIDTH = 220;
/** 两列卡片下表格仅剩半栏宽；全列宽和（110+220+140+96+130+180，含标题列）作为横向滚动下限，既避免表格溢出卡片、也避免列被挤压。 */
const PENDING_TABLE_WIDTH = 876;
/** 全列宽和（110+220+120+96+180，含标题列），同待处理表格的横向滚动兜底。 */
const CREATED_TABLE_WIDTH = 726;

const REASON_KEYS = {
  AWAITING_DECISION: 'pending.reasonAWAITING_DECISION',
  AWAITING_HANDLING: 'pending.reasonAWAITING_HANDLING',
  IN_PROGRESS: 'pending.reasonIN_PROGRESS',
} as const;
const REASON_COLORS: Record<string, string> = {
  AWAITING_DECISION: 'gold',
  AWAITING_HANDLING: 'blue',
  IN_PROGRESS: 'default',
};

function reasonKey(value: string): (typeof REASON_KEYS)[keyof typeof REASON_KEYS] | 'pending.reasonUnknown' {
  return Object.prototype.hasOwnProperty.call(REASON_KEYS, value)
    ? REASON_KEYS[value as keyof typeof REASON_KEYS]
    : 'pending.reasonUnknown';
}

function percent(value: number) {
  return `${value > 0 ? '+' : ''}${formatNumber(value, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`;
}

function today() {
  return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: TIMEZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()));
}

function createdStatusColor(category: string | null) {
  if (category === 'DONE') return 'success';
  if (category === 'CANCELED') return 'default';
  return 'processing';
}

export function DeliveryOverviewPage() {
  const { t } = useTranslation('overview');
  const navigate = useNavigate();
  const [rangeKey, setRangeKey] = useState<'week' | 'month'>('week');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [createdPage, setCreatedPage] = useState(1);
  const [createdPageSize, setCreatedPageSize] = useState(10);
  const days = RANGE_DAYS[rangeKey];
  const { start, end } = useMemo(() => {
    const endDay = today();
    return { start: endDay.subtract(days - 1, 'day').format('YYYY-MM-DD'), end: endDay.format('YYYY-MM-DD') };
  }, [days]);

  const overviewQuery = useDeliveryOverview(start, end);
  const pendingQuery = usePendingWorkitems(page, pageSize);
  const createdQuery = useCreatedWorkitems(start, end, createdPage, createdPageSize);
  const data = overviewQuery.data;
  const pendingPage = pendingQuery.data;
  const createdPageData = createdQuery.data;
  const trend = (data?.created.daily ?? []).map((day) => day.count);
  const rangeLabel = t(rangeKey === 'week' ? 'toolbar.lastWeek' : 'toolbar.lastMonth');
  const refresh = () => {
    void overviewQuery.refetch();
    void pendingQuery.refetch();
    void createdQuery.refetch();
  };

  const columns: ColumnsType<PendingWorkitem> = [
    {
      title: t('pending.workitem'), dataIndex: 'id', width: 110, align: 'left',
      render: (id: number) => (
        <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${id}`); }} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a>
      ),
    },
    {
      title: t('pending.name'), dataIndex: 'title', width: TITLE_COLUMN_WIDTH, align: 'left', ellipsis: { showTitle: false },
      render: (title: string, record: PendingWorkitem) => (
        <EllipsisText tooltip={title}>
          <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${record.id}`); }}>{title}</a>
        </EllipsisText>
      ),
    },
    {
      title: t('pending.status'), dataIndex: 'statusName', width: 140,
      render: (name: string | null) => name ? <Tag color="processing" style={{ margin: 0 }}>{name}</Tag> : '—',
    },
    {
      title: t('pending.priority'), dataIndex: 'priority', width: 96,
      render: (priority: number) => {
        const meta = getPriorityMeta(priority);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: t('pending.reason'), dataIndex: 'reason', width: 130,
      render: (reason: string) => <Tag color={REASON_COLORS[reason] ?? 'default'} style={{ margin: 0 }}>{t(reasonKey(reason))}</Tag>,
    },
    {
      title: t('pending.updatedAt'), dataIndex: 'gmtModified', width: 180,
      render: (value: string) => formatDateTime(value, DATE_TIME_OPTIONS),
    },
  ];

  const createdColumns: ColumnsType<CreatedWorkitem> = [
    {
      title: t('createdList.workitem'), dataIndex: 'id', width: 110, align: 'left',
      render: (id: number) => (
        <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${id}`); }} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a>
      ),
    },
    {
      title: t('createdList.name'), dataIndex: 'title', width: TITLE_COLUMN_WIDTH, align: 'left', ellipsis: { showTitle: false },
      render: (title: string, record: CreatedWorkitem) => (
        <EllipsisText tooltip={title}>
          <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${record.id}`); }}>{title}</a>
        </EllipsisText>
      ),
    },
    {
      title: t('createdList.status'), dataIndex: 'statusName', width: 120,
      render: (name: string | null, record: CreatedWorkitem) => name
        ? <Tag color={createdStatusColor(record.statusCategory)} style={{ margin: 0 }}>{name}</Tag>
        : '—',
    },
    {
      title: t('createdList.priority'), dataIndex: 'priority', width: 96,
      render: (priority: number) => {
        const meta = getPriorityMeta(priority);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: t('createdList.createdAt'), dataIndex: 'createdAt', width: 180,
      render: (value: string) => formatDateTime(value, DATE_TIME_OPTIONS),
    },
  ];

  return (
    <div className="delivery-overview-page">
      <PageHeading title={t('title')} description={t('description')} extra={
        <Segmented
          aria-label={t('toolbar.rangeAria')}
          value={rangeKey}
          onChange={(value) => { setRangeKey(value as 'week' | 'month'); setPage(1); setCreatedPage(1); }}
          options={[
            { label: t('toolbar.lastWeek'), value: 'week' },
            { label: t('toolbar.lastMonth'), value: 'month' },
          ]}
        />
      } />

      <div className="overview-toolbar">
        <Typography.Text type="secondary">{t('toolbar.rangeSummary', { start, end })}</Typography.Text>
        <Typography.Text type="secondary">{t('toolbar.timezone', { timezone: data?.timezone ?? TIMEZONE })}</Typography.Text>
        {data && <Typography.Text type="secondary">{t('toolbar.dataThrough', { date: data.dataThrough })}</Typography.Text>}
        <Button
          aria-label={t('toolbar.refreshAria')}
          loading={overviewQuery.isFetching || pendingQuery.isFetching || createdQuery.isFetching ? { icon: <LoadingOutlined aria-hidden="true" /> } : false}
          onClick={refresh}
        >{t('toolbar.refresh')}</Button>
      </div>

      {overviewQuery.isError && (
        <Alert
          type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('toolbar.loadFailure')}
          action={<Button size="small" onClick={() => { void overviewQuery.refetch(); }}>{t('toolbar.retry')}</Button>}
        />
      )}

      <div className="overview-stats">
        <Card data-testid="overview-created" className="overview-stat-card">
          <Statistic loading={overviewQuery.isLoading} title={t('created.title')} value={data ? formatNumber(data.created.count) : '—'} />
          <div className="overview-stat-detail">
            <div className="overview-trend">
              <span className="overview-sparkline" role="img" aria-label={t('created.trendAria', { days: data?.daysInRange ?? days })}>
                <Sparkline data={trend} color="var(--aw-accent)" width={120} height={32} />
              </span>
              <span>{t('created.caption', { days: data?.daysInRange ?? days })}</span>
            </div>
          </div>
        </Card>
        <Card data-testid="overview-delivered" className="overview-stat-card">
          <Statistic
            loading={overviewQuery.isLoading}
            title={<Space size={6}>{t('delivered.title')}
              <Tooltip title={t('delivered.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}>
                <Button type="text" size="small" aria-label={t('delivered.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--aw-muted)' }} />
              </Tooltip>
            </Space>}
            value={data ? formatNumber(data.delivered.count) : '—'}
          />
          <div className="overview-stat-detail">
            {data && <>
              <div>{t('delivered.dailyAverage', { value: formatNumber(data.delivered.dailyAverage, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) })}</div>
              <div>{data.delivered.changePercent != null ? t('delivered.change', { value: percent(data.delivered.changePercent) }) : t('delivered.noBase')}</div>
              <div>{t('delivered.previous', { count: data.delivered.previous.count, start: data.delivered.previous.startDate, end: data.delivered.previous.endDate })}</div>
              <div>{t('delivered.caption')}</div>
            </>}
          </div>
        </Card>
      </div>

      <div className="overview-lists">
        <Card data-testid="overview-pending" title={<Space size={8}>{t('pending.title')}<Tag>{t('pending.caption')}</Tag></Space>}>
          <p className="overview-caption">{t('pending.hint')}</p>
          {pendingQuery.isError && (
            <Alert
              type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('pending.loadFailure')}
              action={<Button size="small" onClick={() => { void pendingQuery.refetch(); }}>{t('pending.retry')}</Button>}
              style={{ marginBottom: 12 }}
            />
          )}
          <Table<PendingWorkitem>
            rowKey="id"
            dataSource={pendingPage?.list ?? []}
            loading={pendingQuery.isLoading}
            columns={columns}
            scroll={{ x: PENDING_TABLE_WIDTH }}
            rowClassName={(record) => record.statusCategory === 'PENDING_DECISION' ? 'overview-row-decision' : ''}
            onRow={(record) => ({ onClick: () => navigate(`/workitems/${record.id}`), style: { cursor: 'pointer' } })}
            locale={{ emptyText: pendingQuery.isLoading ? t('pending.loading') : pendingQuery.isError ? t('pending.loadFailure') : t('pending.empty') }}
            pagination={{
              current: page,
              pageSize,
              total: pendingPage?.total ?? 0,
              showSizeChanger: false,
              showTotal: (total) => t('pending.total', { count: total }),
              onChange: (nextPage, nextPageSize) => { setPage(nextPage); setPageSize(nextPageSize); },
            }}
          />
        </Card>
        <Card data-testid="overview-created-list" title={<Space size={8}>{t('createdList.title', { range: rangeLabel })}<Tag>{t('createdList.caption')}</Tag></Space>}>
          <p className="overview-caption">{t('createdList.hint')}</p>
          {createdQuery.isError && (
            <Alert
              type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('createdList.loadFailure')}
              action={<Button size="small" onClick={() => { void createdQuery.refetch(); }}>{t('createdList.retry')}</Button>}
              style={{ marginBottom: 12 }}
            />
          )}
          <Table<CreatedWorkitem>
            rowKey="id"
            dataSource={createdPageData?.list ?? []}
            loading={createdQuery.isLoading}
            columns={createdColumns}
            scroll={{ x: CREATED_TABLE_WIDTH }}
            onRow={(record) => ({ onClick: () => navigate(`/workitems/${record.id}`), style: { cursor: 'pointer' } })}
            locale={{ emptyText: createdQuery.isLoading ? t('createdList.loading') : createdQuery.isError ? t('createdList.loadFailure') : t('createdList.empty') }}
            pagination={{
              current: createdPage,
              pageSize: createdPageSize,
              total: createdPageData?.total ?? 0,
              showSizeChanger: false,
              showTotal: (total) => t('createdList.total', { count: total }),
              onChange: (nextPage, nextPageSize) => { setCreatedPage(nextPage); setCreatedPageSize(nextPageSize); },
            }}
          />
        </Card>
      </div>
    </div>
  );
}
