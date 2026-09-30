import { useEffect, useRef, useState } from 'react';
import { Button, Spin, Typography } from 'antd';
import type { RuntimeActivity } from '@/shared/types/workitem';
import { getRuntimeActivities } from '../api';

const { Text } = Typography;
const ACTIVE_DISPATCH_STATUSES = new Set(['RUNNING', 'ACKED', 'DISPATCHED', 'PAUSING', 'PAUSE_FAILED']);

function displayTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

interface DispatchActivityFeedProps {
  dispatchId: number;
  status?: string | null;
}

export function DispatchActivityFeed({ dispatchId, status }: DispatchActivityFeedProps) {
  const [activities, setActivities] = useState<RuntimeActivity[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const active = ACTIVE_DISPATCH_STATUSES.has(status?.toUpperCase() ?? '');
  const lifecycleGeneration = useRef(0);
  const displayedDispatchId = useRef(dispatchId);
  const loadRef = useRef<(() => void) | null>(null);
  const titleId = `dispatch-activities-title-${dispatchId}`;

  useEffect(() => {
    let cancelled = false;
    let inFlight = false;
    let timer: number | null = null;
    let requestController: AbortController | null = null;
    const generation = ++lifecycleGeneration.current;
    const dispatchChanged = displayedDispatchId.current !== dispatchId;
    displayedDispatchId.current = dispatchId;

    const clearScheduledLoad = () => {
      if (timer != null) {
        window.clearTimeout(timer);
        timer = null;
      }
    };

    if (dispatchChanged) {
      setActivities([]);
      setLoadFailed(false);
    }
    setLoading(true);

    const load = async () => {
      if (cancelled || generation !== lifecycleGeneration.current || inFlight) return;

      clearScheduledLoad();
      requestController?.abort();
      const controller = new AbortController();
      requestController = controller;
      inFlight = true;
      setLoading(true);
      try {
        const timeline = await getRuntimeActivities(dispatchId, controller.signal);
        if (cancelled || generation !== lifecycleGeneration.current) return;
        setActivities(timeline.activities ?? []);
        setLoadFailed(false);
      } catch {
        if (!cancelled && generation === lifecycleGeneration.current && !controller.signal.aborted) {
          setLoadFailed(true);
        }
      } finally {
        if (requestController === controller) requestController = null;
        inFlight = false;

        if (!cancelled && generation === lifecycleGeneration.current) {
          setLoading(false);
          if (active) {
            timer = window.setTimeout(() => {
              timer = null;
              void load();
            }, 2_000);
          }
        }
      }
    };

    const triggerLoad = () => void load();
    loadRef.current = triggerLoad;
    triggerLoad();

    return () => {
      cancelled = true;
      requestController?.abort();
      clearScheduledLoad();
      if (lifecycleGeneration.current === generation) lifecycleGeneration.current += 1;
      if (loadRef.current === triggerLoad) loadRef.current = null;
    };
  }, [dispatchId, active]);

  return (
    <section
      aria-labelledby={titleId}
      data-testid={`dispatch-activities-${dispatchId}`}
      style={{ margin: '6px 0 8px 4px', paddingLeft: 9, borderLeft: '2px solid var(--aw-border)' }}
    >
      <h4 id={titleId} style={{ margin: '0 0 4px', fontSize: 12 }}>执行详情</h4>
      {loading && (
        <div role="status" aria-label="正在加载执行详情">
          <Spin size="small" />
        </div>
      )}
      {loadFailed && (
        <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Text type="danger" style={{ fontSize: 12 }}>执行详情加载失败</Text>
          <Button type="link" size="small" aria-label="重试执行详情" onClick={() => loadRef.current?.()}>
            重试执行详情
          </Button>
        </div>
      )}
      {!loading && !loadFailed && activities.length === 0 && (
        <Text type="secondary" style={{ fontSize: 12 }}>暂无执行详情</Text>
      )}
      {activities.length > 0 && (
        <ul aria-labelledby={titleId} style={{ margin: 0, paddingLeft: 16 }}>
          {activities.map((activity, index) => (
            <li key={activity.eventId ?? `${activity.seq ?? 'activity'}:${index}`} style={{ marginTop: index === 0 ? 0 : 7 }}>
              <Text type="secondary" style={{ display: 'block', fontSize: 11 }}>
                {displayTime(activity.eventTime)} · {activity.eventType}
              </Text>
              <Text
                type={activity.level === 'ERROR' ? 'danger' : undefined}
                style={{
                  display: 'block',
                  whiteSpace: 'pre-wrap',
                  overflowWrap: 'anywhere',
                  fontSize: 12,
                }}
              >
                {activity.content}
              </Text>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
