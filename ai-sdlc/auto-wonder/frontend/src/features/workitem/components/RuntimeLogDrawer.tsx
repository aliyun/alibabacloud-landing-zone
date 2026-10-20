import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Input, Space, Switch, Typography } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { ReloadOutlined, LoadingOutlined, SearchOutlined, InfoCircleFilled, CloseCircleFilled } from '@ant-design/icons';
import { aggregateRuntimeMessages, type RuntimeLogRow } from './runtimeLogMessages';
import { RuntimeTerminal } from './RuntimeTerminal';
import { getRuntimeLog } from '../api';
import { DispatchGuidanceInput } from './DispatchGuidanceInput';
import type { RuntimeTraceEvent } from '@/shared/types/workitem';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';

export function RuntimeLogDrawer({ dispatchId, onClose, terminal = false }: { dispatchId: number; onClose: () => void; terminal?: boolean }) {
  const { t } = useTranslation('workitem');
  return <Drawer open onClose={onClose} width="min(1200px, 96vw)" title={t(terminal ? 'runtimeLog.terminalTitle' : 'runtimeLog.title', { id: dispatchId })}>
    <RuntimeLogPanel dispatchId={dispatchId} terminal={terminal} />
  </Drawer>;
}

export function RuntimeLogPanel({ dispatchId, terminal = false, enabled = true, embedded = false }: { dispatchId: number; terminal?: boolean; enabled?: boolean; embedded?: boolean }) {
  const { t } = useTranslation('workitem');
  const [events, setEvents] = useState<RuntimeTraceEvent[]>([]);
  const [pageSize, setPageSize] = usePageSizePreference('autowonder.runtimeLog.pageSize', [10, 20, 50, 100], 10);
  const [aggregate, setAggregate] = useState(true);
  const [auto, setAuto] = useState(true);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<{ message?: string } | null>(null);
  const [updated, setUpdated] = useState<string | null>(null);
  const refresh = useRef<() => void>(() => {});
  const autoRef = useRef(auto);
  autoRef.current = auto && enabled;

  useEffect(() => {
    let disposed = false;
    let busy = false;
    let seq: number | null = null;
    const controller = new AbortController();
    setEvents([]); setError(null); setUpdated(null);
    const load = async () => {
      if (busy || disposed) return;
      busy = true; setLoading(true);
      try {
        const data = await getRuntimeLog(dispatchId, seq, controller.signal);
        if (disposed) return;
        if (seq == null || data.changed) setEvents(data.events ?? []);
        seq = data.lastSeq ?? seq;
        setUpdated(new Date().toISOString()); setError(null);
      } catch (e) {
        if (!disposed) setError({ message: e instanceof Error ? e.message : undefined });
      } finally {
        busy = false;
        if (!disposed) setLoading(false);
      }
    };
    refresh.current = () => { void load(); };
    void load();
    const timer = window.setInterval(() => { if (autoRef.current) void load(); }, 3000);
    return () => { disposed = true; controller.abort(); window.clearInterval(timer); };
  }, [dispatchId]);

  const rows: RuntimeLogRow[] = aggregate ? aggregateRuntimeMessages(events) : events;
  const filtered = rows.filter(event => !query || JSON.stringify(event).toLowerCase().includes(query.toLowerCase()));
  return <div>
    <Space wrap style={{ marginBottom: 16 }}>
      <Input.Search enterButton={<Button aria-label={t('runtimeLog.search')} icon={<SearchOutlined aria-hidden="true" />} />} aria-label={t('runtimeLog.search')} placeholder={t('runtimeLog.search')} allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('runtimeLog.clearSearch')} /> }} value={query} onChange={e => setQuery(e.target.value)} style={{ width: 'min(300px, 100%)' }} />
      {!terminal && <Switch checked={aggregate} onChange={setAggregate} checkedChildren={t('runtimeLog.aggregate')} unCheckedChildren={t('runtimeLog.raw')} />}
      <Switch checked={auto} onChange={setAuto} checkedChildren={t('runtimeLog.auto')} unCheckedChildren={t('runtimeLog.paused')} />
      <Button aria-label={t('runtimeLog.refresh')} icon={<ReloadOutlined aria-hidden="true" />} loading={loading ? { icon: <LoadingOutlined aria-hidden="true" /> } : false} onClick={() => refresh.current()}>{t('runtimeLog.refresh')}</Button>
      <Typography.Text type="secondary">{t('runtimeLog.refreshSummary', { count: events.length })}{updated ? t('runtimeLog.updated', { time: formatDateTime(updated, { timeStyle: 'medium' }) }) : ''}</Typography.Text>
    </Space>
    {!embedded && <Alert type="info" icon={<InfoCircleFilled aria-hidden="true" />} showIcon message={t(terminal ? 'runtimeLog.terminalHint' : 'runtimeLog.hint')} style={{ marginBottom: 12 }} />}
    {error && <Alert type="error" icon={<CloseCircleFilled aria-hidden="true" />} showIcon message={error.message ?? t('runtimeLog.loadFailed')} style={{ marginBottom: 12 }} />}
    {terminal ? <><RuntimeTerminal events={events} query={query} height={embedded ? 'clamp(220px, calc(100dvh - 480px), 650px)' : undefined} /><DispatchGuidanceInput key={dispatchId} dispatchId={dispatchId} /></> : <Table dataSource={[...filtered].reverse()} loading={loading && !events.length} size="small"
      rowKey={event => event.eventId ?? `${event.seq}-${event.eventType}`}
      pagination={{ pageSize, showSizeChanger: true, pageSizeOptions: [20, 50, 100], onChange: (_p, ps) => setPageSize(ps), showTotal: total => t('runtimeLog.total', { count: total }) }}
      columns={[
        { title: t('runtimeLog.sequence'), width: 110, render: (_, event) => event.fragments && event.fragments.length > 1 ? `${event.seq}–${event.endSeq}` : event.seq },
        { title: t('runtimeLog.time'), dataIndex: 'eventTime', width: 190, render: (value: string) => value ? formatDateTime(value) : '—' },
        { title: t('runtimeLog.event'), dataIndex: 'eventType', width: 190 },
        { title: t('runtimeLog.content'), align: 'left', render: (_, event) => <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 100, overflow: 'auto' }}>{event.messageText ?? String(event.detail.outputSummary || event.detail.inputSummary || event.detail.contentSummary || event.detail.message || event.eventType)}</div> },
      ]}
      expandable={{ expandedRowRender: event => <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: '55vh', overflow: 'auto' }}>{JSON.stringify(event.fragments && event.fragments.length > 1 ? { message: event.messageText, fragmentCount: event.fragments.length, events: event.fragments } : event.detail, null, 2)}</pre> }} />}
  </div>;
}
