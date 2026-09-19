import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Input, Space, Switch, Table, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { aggregateRuntimeMessages, type RuntimeLogRow } from './runtimeLogMessages';
import { getRuntimeLog } from '../api';
import type { RuntimeTraceEvent } from '@/shared/types/workitem';

export function RuntimeLogDrawer({ dispatchId, onClose }: { dispatchId: number; onClose: () => void }) {
  const [events, setEvents] = useState<RuntimeTraceEvent[]>([]);
  const [aggregate, setAggregate] = useState(true);
  const [auto, setAuto] = useState(true);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [updated, setUpdated] = useState<string | null>(null);
  const refresh = useRef<() => void>(() => {});
  const autoRef = useRef(auto);
  autoRef.current = auto;

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
        setUpdated(new Date().toLocaleTimeString()); setError(null);
      } catch (e) {
        if (!disposed) setError(e instanceof Error ? e.message : '日志加载失败');
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
  return <Drawer open onClose={onClose} width="min(1200px, 96vw)" title={`执行日志 · Dispatch #${dispatchId}`}>
    <Space wrap style={{ marginBottom: 16 }}>
      <Input.Search placeholder="搜索事件、工具、输入或输出" allowClear value={query} onChange={e => setQuery(e.target.value)} style={{ width: 300 }} />
      <Switch checked={aggregate} onChange={setAggregate} checkedChildren="聚合消息" unCheckedChildren="原始事件" />
      <Switch checked={auto} onChange={setAuto} checkedChildren="自动刷新" unCheckedChildren="暂停刷新" />
      <Button icon={<ReloadOutlined />} loading={loading} onClick={() => refresh.current()}>刷新</Button>
      <Typography.Text type="secondary">每 3 秒刷新 · {events.length} 条{updated ? ` · 更新于 ${updated}` : ''}</Typography.Text>
    </Space>
    <Alert type="info" showIcon message="展示平台已保存的全部事件，可展开查看字段详情。已脱敏或在上报时截断的内容无法还原。" style={{ marginBottom: 12 }} />
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
    <Table dataSource={[...filtered].reverse()} loading={loading && !events.length} size="small"
      rowKey={event => event.eventId ?? `${event.seq}-${event.eventType}`}
      pagination={{ defaultPageSize: 50, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: total => `共 ${total} 条` }}
      columns={[
        { title: '序号', width: 110, render: (_, event) => event.fragments && event.fragments.length > 1 ? `${event.seq}–${event.endSeq}` : event.seq },
        { title: '时间', dataIndex: 'eventTime', width: 190, render: (value: string) => value ? new Date(value).toLocaleString() : '—' },
        { title: '事件', dataIndex: 'eventType', width: 190 },
        { title: '内容', render: (_, event) => <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 100, overflow: 'auto' }}>{event.messageText ?? String(event.detail.outputSummary || event.detail.inputSummary || event.detail.contentSummary || event.detail.message || event.eventType)}</div> },
      ]}
      expandable={{ expandedRowRender: event => <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: '55vh', overflow: 'auto' }}>{JSON.stringify(event.fragments && event.fragments.length > 1 ? { message: event.messageText, fragmentCount: event.fragments.length, events: event.fragments } : event.detail, null, 2)}</pre> }} />
  </Drawer>;
}
