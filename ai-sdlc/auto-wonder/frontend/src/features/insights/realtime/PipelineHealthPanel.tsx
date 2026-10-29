import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Empty, Segmented, Spin, Tooltip } from 'antd';
import { HeartOutlined, QuestionCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { formatMinutesCompact } from '@/shared/lib/duration';
import { usePipelineHealth } from './hooks';
import FlashValue from './FlashValue';
import type { PipelineRange } from './api';

const RANGE_LABEL_KEYS = {
  today: 'pipeline.ranges.today',
  '7d': 'pipeline.ranges.7d',
  '30d': 'pipeline.ranges.30d',
  '90d': 'pipeline.ranges.90d',
} as const satisfies Record<PipelineRange, string>;

const RANGE_DAYS: Record<PipelineRange, number> = { today: 0, '7d': 7, '30d': 30, '90d': 90 };
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

function shanghaiMidnight(now: Date): number {
  const shifted = new Date(now.getTime() + SHANGHAI_OFFSET_MS);
  shifted.setUTCHours(0, 0, 0, 0);
  return shifted.getTime() - SHANGHAI_OFFSET_MS;
}

function formatEdge(ms: number): string {
  const shifted = new Date(ms + SHANGHAI_OFFSET_MS);
  return `${shifted.getUTCFullYear()}-${pad2(shifted.getUTCMonth() + 1)}-${pad2(shifted.getUTCDate())} ${pad2(shifted.getUTCHours())}:${pad2(shifted.getUTCMinutes())}`;
}

function formatRate(rate: number | null, empty: string): string {
  return rate == null ? empty : `${rate.toFixed(1)}%`;
}

export default function PipelineHealthPanel() {
  const { t } = useTranslation('insights');
  const [range, setRange] = useState<PipelineRange>('today');
  const { data, isLoading, isError, error, isFetching, refetch } = usePipelineHealth(range);
  const rangeLabel = t(RANGE_LABEL_KEYS[range]);

  const today = shanghaiMidnight(new Date());
  const days = RANGE_DAYS[range];
  const windowStart = days === 0 ? today : today - days * DAY_MS;
  const windowEnd = days === 0 ? today + DAY_MS : today;
  const windowLabel = t(days === 0 ? 'pipeline.windowToday' : 'pipeline.windowPast', {
    range: rangeLabel,
    start: formatEdge(windowStart),
    end: formatEdge(windowEnd),
  });

  const emptyValue = t('pipeline.emptyValue');
  const showRealtimeTag = range === 'today';
  const avgTooltip = data ? (
    <div className="insight-pl-tip">
      <div>{t('pipeline.avgTooltipCaliber')}</div>
      <div>{t('pipeline.avgTooltipSource')}</div>
    </div>
  ) : null;
  const reworkTooltip = data ? (
    <div className="insight-pl-tip">
      <div>{t('pipeline.reworkTooltipRetry', { retries: data.rework.retryCount })}</div>
      <div>
        {t('pipeline.reworkTooltipFormula', {
          reworked: data.rework.reworkedWorkitems,
          executed: data.rework.executedWorkitems,
          range: rangeLabel,
        })}
      </div>
    </div>
  ) : null;

  return (
    <section className="insight-panel insight-panel--pl" aria-label={t('pipeline.title')}>
      <div className="insight-panel-head">
        <div className="insight-panel-id">
          <span className="insight-panel-glyph insight-panel-glyph--pl" aria-hidden="true">
            <HeartOutlined />
          </span>
          <h2>{t('pipeline.title')}</h2>
          <span className="insight-pl-chip" title={windowLabel} aria-label={windowLabel}>
            <span>{t('pipeline.chipPrefix')}</span>
            <b>{rangeLabel}</b>
          </span>
          {showRealtimeTag && (
            <span className="insight-pl-realtime" title={t('pipeline.realtimeTag')} aria-label={t('pipeline.realtimeTag')}>
              {t('pipeline.realtimeTag')}
            </span>
          )}
          {isFetching && data && <span className="insight-stamp is-updating">{t('pipeline.updating')}</span>}
        </div>
        <div className="insight-panel-tools">
          <Segmented<PipelineRange>
            aria-label={t('pipeline.rangeAria')}
            value={range}
            onChange={(value) => setRange(value)}
            options={(['today', '7d', '30d', '90d'] as const).map((value) => ({ value, label: t(RANGE_LABEL_KEYS[value]) }))}
          />
        </div>
      </div>
      <p className="insight-panel-note">
        <span>{t('pipeline.notePrefix')}</span>
        <b>{t('pipeline.noteScope')}</b>
        <span className="insight-dot-sep" aria-hidden="true" />
        <span>{t('pipeline.noteRefresh')}</span>
        <span className="insight-dot-sep" aria-hidden="true" />
        <span>{t('pipeline.noteIndependent')}</span>
      </p>

      {isError && (
        <Alert
          type="error"
          showIcon
          message={t('pipeline.loadFailure')}
          description={(error as Error)?.message ?? t('realtime.retryLater')}
          action={(
            <Button
              size="small"
              icon={<ReloadOutlined aria-hidden="true" />}
              onClick={() => { void refetch(); }}
            >
              {t('pipeline.retry')}
            </Button>
          )}
          style={{ marginTop: 12 }}
        />
      )}

      <div className="insight-panel-body">
        {isLoading && !data ? (
          <div className="insight-panel-state">
            <Spin tip={t('pipeline.loading')}><div style={{ minHeight: 40 }} /></Spin>
          </div>
        ) : data ? (
          <div className="insight-pl-grid">
            <div className="insight-pl-card is-ok">
              <span className="insight-pl-label">{t('pipeline.successRate')}</span>
              <span className="insight-pl-value"><FlashValue value={formatRate(data.successRate, emptyValue)} /></span>
              <span className="insight-pl-sub">
                {t('pipeline.successSub', { succeeded: data.successSub.succeeded, total: data.successSub.total })}
              </span>
            </div>
            <div className="insight-pl-card is-bad">
              <span className="insight-pl-label">{t('pipeline.failedOrTimeout')}</span>
              <span className="insight-pl-value"><FlashValue value={data.failedOrTimeout} /></span>
              <span className="insight-pl-sub">{t('pipeline.failedSub')}</span>
            </div>
            <Tooltip title={reworkTooltip} trigger={['hover', 'focus']}>
              <div
                className="insight-pl-card is-warn has-tip"
                tabIndex={0}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') event.currentTarget.blur();
                }}
              >
                <QuestionCircleOutlined className="insight-pl-tip-dot" aria-hidden="true" />
                <span className="insight-pl-label">{t('pipeline.reworkRate')}</span>
                <span className="insight-pl-value"><FlashValue value={formatRate(data.rework.rate, emptyValue)} /></span>
                <span className="insight-pl-sub">
                  {t('pipeline.reworkSub', { reworked: data.rework.reworkedWorkitems, executed: data.rework.executedWorkitems })}
                </span>
              </div>
            </Tooltip>
            <Tooltip title={avgTooltip} trigger={['hover', 'focus']}>
              <div
                className="insight-pl-card is-neutral has-tip"
                tabIndex={0}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') event.currentTarget.blur();
                }}
              >
                <QuestionCircleOutlined className="insight-pl-tip-dot" aria-hidden="true" />
                <span className="insight-pl-label">{t('pipeline.avgDuration')}</span>
                <span className="insight-pl-value">
                  <FlashValue value={data.avgWorkitemDurationMinutes == null ? emptyValue : formatMinutesCompact(data.avgWorkitemDurationMinutes)} />
                </span>
                <span className="insight-pl-sub">{t('pipeline.avgSub')}</span>
              </div>
            </Tooltip>
          </div>
        ) : !isError ? <Empty description={t('pipeline.empty')} /> : null}
      </div>
    </section>
  );
}
