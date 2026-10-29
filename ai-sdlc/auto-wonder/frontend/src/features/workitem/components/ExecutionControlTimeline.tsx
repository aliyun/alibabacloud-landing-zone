import { useState } from 'react';
import { Button, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import type { TimelineItem } from '@/shared/types/workitem';
import { executionControlSummary } from '../runtimeLabels';

export function ExecutionControlTimeline({ dispatchId, items, compact = false }: { dispatchId: number; items: TimelineItem[]; compact?: boolean }) {
  const { t } = useTranslation('workitem');
  const [visibleCount, setVisibleCount] = useState(compact ? 1 : 10);
  // A resume belongs to both executions; delivery controls belong only to affected executions.
  const events = items.filter(item => item.type === 'system' && (
    item.executionControl && [item.executionControl.dispatchId, item.executionControl.sourceDispatchId,
      item.executionControl.targetDispatchId].includes(dispatchId)
    || item.deliveryControl?.affectedDispatches.some(affected => affected.dispatchId === dispatchId)
  ));
  const timeOptions: Intl.DateTimeFormatOptions | undefined = compact ? { hour: '2-digit', minute: '2-digit' } : undefined;
  const record = (item: TimelineItem) => {
    const name = item.executionControl?.requestedByName || item.deliveryControl?.requestedByName || item.operatorDisplayName;
    const action = executionControlSummary(item, dispatchId);
    return name ? t('executionControl.record', { name, action }) : action;
  };
  if (!events.length) return null;
  return <section aria-label={t('executionControl.timeline')} data-testid={`execution-control-timeline-${dispatchId}`}
    className={`execution-control-records ${compact ? 'is-compact' : ''}`}>
    {compact && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('executionControl.timeline')}</Typography.Text>}
    <ol style={{ listStyle: 'none', padding: 0, margin: '6px 0' }}>
      {events.slice(0, visibleCount).map(item => <li key={item.id}
        className="execution-control-record">
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          <time dateTime={item.gmtCreate} title={formatDateTime(item.gmtCreate)}>{formatDateTime(item.gmtCreate, timeOptions)}</time>
        </Typography.Text>
        <div>
          <div>{record(item)}</div>
          {item.executionControl?.reason && <Typography.Text type="secondary">{t('executionControl.reason', { reason: item.executionControl.reason })}</Typography.Text>}
        </div>
      </li>)}
    </ol>
    {events.length > visibleCount && <Button type="link" size="small" onClick={() => setVisibleCount(count => count + 10)}>
      {t('executionControl.loadEarlier', { count: Math.min(10, events.length - visibleCount) })}
    </Button>}
  </section>;
}
