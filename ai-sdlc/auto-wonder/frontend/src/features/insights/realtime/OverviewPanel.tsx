import { useTranslation } from 'react-i18next';
import { Alert, Button, Empty, Spin, Tooltip } from 'antd';
import { LineChartOutlined, ReloadOutlined } from '@ant-design/icons';
import { formatDateTime } from '@/shared/i18n/format';
import { useOverviewDashboard } from './hooks';
import KpiRow, { type KpiKey } from './KpiRow';
import FlashValue from './FlashValue';
import type { Inventory } from './api';

interface Props {
  onKpiClick?: (key: KpiKey) => void;
}

function formatUpdatedAt(iso: string | undefined): string {
  if (!iso) return '—';
  if (Number.isNaN(new Date(iso).getTime())) return iso;
  return formatDateTime(iso, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

function sharePercent(value: number, total: number): string {
  if (!total) return '0.0%';
  return `${((value / total) * 100).toFixed(1)}%`;
}

function shareWidth(value: number, total: number): string {
  if (!total) return '0%';
  return `${((value / total) * 100).toFixed(2)}%`;
}

function barWidth(value: number, max: number): string {
  if (!max) return '0%';
  return `${((value / max) * 100).toFixed(1)}%`;
}

const CATEGORY_KEYS = [
  { key: 'req', labelKey: 'overview.category.req', color: 'var(--insight-cat-req)' },
  { key: 'bug', labelKey: 'overview.category.bug', color: 'var(--insight-cat-bug)' },
  { key: 'task', labelKey: 'overview.category.task', color: 'var(--insight-cat-task)' },
] as const;

const STATUS_KEYS = [
  { key: 'init', labelKey: 'overview.status.init', color: 'var(--insight-st-todo)' },
  { key: 'inProgress', labelKey: 'overview.status.inProgress', color: 'var(--insight-st-doing)' },
  { key: 'done', labelKey: 'overview.status.done', color: 'var(--insight-st-done)' },
  { key: 'canceled', labelKey: 'overview.status.canceled', color: 'var(--insight-st-cancel)' },
] as const;

function CategoryBars({ inventory }: { inventory: Inventory }) {
  const { t } = useTranslation('insights');
  const values = { req: inventory.byType.req, bug: inventory.byType.bug, task: inventory.byType.task };
  const total = values.req + values.bug + values.task;
  const max = Math.max(values.req, values.bug, values.task);
  return (
    <div className="insight-subcard" aria-label={t('overview.category.title')}>
      <div className="insight-subcard-head">
        <h3>{t('overview.category.title')}</h3>
        <span className="insight-subcard-hint">{t('overview.category.hint')}</span>
      </div>
      <div className="insight-cat-list">
        {CATEGORY_KEYS.map((item) => {
          const value = values[item.key];
          return (
            <div className="insight-cat-row" key={item.key}>
              <span className="insight-cat-label">{t(item.labelKey)}</span>
              <span className="insight-cat-track">
                <span className="insight-cat-fill" style={{ width: barWidth(value, max), background: item.color }} />
              </span>
              <span className="insight-cat-number">
                <b className="insight-cat-value"><FlashValue value={value} /></b>
                <span className="insight-cat-pct">{sharePercent(value, total)}</span>
              </span>
            </div>
          );
        })}
      </div>
      <p className="insight-sub-foot">{t('overview.category.foot')}</p>
    </div>
  );
}

function StatusStack({ inventory }: { inventory: Inventory }) {
  const { t } = useTranslation('insights');
  const values = {
    init: inventory.byLifecycle.init,
    inProgress: inventory.byLifecycle.inProgress,
    done: inventory.byLifecycle.done,
    canceled: inventory.byLifecycle.canceled,
  };
  const total = values.init + values.inProgress + values.done + values.canceled;
  return (
    <div className="insight-subcard" aria-label={t('overview.status.title')}>
      <div className="insight-subcard-head">
        <h3>{t('overview.status.title')}</h3>
        <span className="insight-subcard-hint">{t('overview.status.hint')}</span>
      </div>
      <div className="insight-status-stack" role="img" aria-label={t('overview.status.chartAria')}>
        {STATUS_KEYS.map((item) => {
          const value = values[item.key];
          const label = t('overview.status.segmentLabel', {
            status: t(item.labelKey),
            items: value,
            percent: sharePercent(value, total),
          });
          return (
            <Tooltip key={item.key} title={label}>
              <span
                className="insight-status-seg"
                role="button"
                tabIndex={0}
                aria-label={label}
                style={{ width: shareWidth(value, total), background: item.color }}
              />
            </Tooltip>
          );
        })}
      </div>
      <div className="insight-status-legend">
        {STATUS_KEYS.map((item) => {
          const value = values[item.key];
          return (
            <span className="insight-status-item" key={item.key}>
              <i style={{ background: item.color }} aria-hidden="true" />
              <span>{t(item.labelKey)}</span>
              <b><FlashValue value={value} /></b>
              <span className="insight-status-pct">{sharePercent(value, total)}</span>
            </span>
          );
        })}
      </div>
      <p className="insight-sub-foot">{t('overview.status.foot')}</p>
    </div>
  );
}

export default function OverviewPanel({ onKpiClick }: Props) {
  const { t } = useTranslation('insights');
  const { data, isLoading, isError, error, isFetching, refetch } = useOverviewDashboard();

  return (
    <section className="insight-panel insight-panel--ov" aria-label={t('overview.title')}>
      <div className="insight-panel-head">
        <div className="insight-panel-id">
          <span className="insight-panel-glyph insight-panel-glyph--ov" aria-hidden="true">
            <LineChartOutlined />
          </span>
          <h2>{t('overview.title')}</h2>
          <span className="insight-live-badge" role="status" aria-label={t('overview.autoRefreshLabel')}>
            <i aria-hidden="true" />
            <span>{t('overview.autoRefreshLabel')}</span>
            <b>{t('overview.autoRefreshValue')}</b>
          </span>
          <span className="insight-stamp">{t('overview.updatedAtLabel')} <b>{formatUpdatedAt(data?.generatedAt)}</b></span>
          {isFetching && data && <span className="insight-stamp is-updating">{t('overview.updating')}</span>}
        </div>
        <div className="insight-panel-tools">
          <Button
            icon={<ReloadOutlined aria-hidden="true" />}
            loading={isFetching}
            onClick={() => { void refetch(); }}
          >
            {t('overview.refresh')}
          </Button>
        </div>
      </div>
      <p className="insight-panel-note">
        <span>{t('overview.notePrefix')}</span>
        <b>{t('overview.noteScope')}</b>
        <span className="insight-dot-sep" aria-hidden="true" />
        <span>{t('overview.noteRefresh')}</span>
        <span className="insight-dot-sep" aria-hidden="true" />
        <span>{t('overview.noteRange')}</span>
      </p>

      {isError && (
        <Alert
          type="error"
          showIcon
          message={t('overview.loadFailure')}
          description={(error as Error)?.message ?? t('realtime.retryLater')}
          action={<Button size="small" onClick={() => { void refetch(); }}>{t('overview.retry')}</Button>}
          style={{ marginTop: 12 }}
        />
      )}

      <div className="insight-panel-body">
        {isLoading && !data ? (
          <div className="insight-panel-state">
            <Spin tip={t('overview.loading')}><div style={{ minHeight: 40 }} /></Spin>
          </div>
        ) : data ? (
          <>
            <KpiRow kpi={data.kpi} onKpiClick={onKpiClick} />
            <div className="insight-ov-grid">
              <CategoryBars inventory={data.inventory} />
              <StatusStack inventory={data.inventory} />
            </div>
          </>
        ) : !isError ? <Empty description={t('overview.empty')} /> : null}
      </div>
    </section>
  );
}
