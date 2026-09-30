import { Card } from 'antd';
import type { Inventory } from './api';
import { BRAND } from './theme';

interface Props {
  inventory: Inventory;
}

export default function InventoryPanel({ inventory }: Props) {
  const { byLifecycle, byType } = inventory;
  const init = byLifecycle.init;
  const inProgress = byLifecycle.inProgress;
  const done = byLifecycle.done;
  const total = init + inProgress + done;
  return (
    <Card title="工单存量" style={{ minWidth: 0 }} styles={{ body: { textAlign: 'center' } }}>
      <div role="img" aria-label={`待开始 ${init}，进行中 ${inProgress}，已完成 ${done}`}
        style={{
          display: 'flex',
          gap: 0,
          height: 14,
          borderRadius: 7,
          overflow: 'hidden',
          marginBottom: 10,
        }}
      >
        <div style={{ flex: init, background: BRAND.grey }} />
        <div style={{ flex: inProgress, background: BRAND.orange }} />
        <div style={{ flex: done, background: BRAND.green }} />
      </div>
      <div style={{ fontSize: 12, color: 'var(--aw-muted)', lineHeight: 1.9 }}>
        <div>
          待开始 <b className="insight-realtime-metric">{init}</b> · 进行中{' '}
          <b className="insight-realtime-metric">{inProgress}</b> · 已完成 <b className="insight-realtime-metric">{done}</b>
          {byLifecycle.canceled > 0 && <> · 已取消 <b className="insight-realtime-metric">{byLifecycle.canceled}</b></>}
        </div>
        <div style={{ marginTop: 8, color: BRAND.textMuted }}>
          需求 <b className="insight-realtime-metric">{byType.req}</b> · 任务 <b className="insight-realtime-metric">{byType.task}</b> · 缺陷 <b className="insight-realtime-metric">{byType.bug}</b>
        </div>
      </div>
      <div style={{ fontSize: 12, color: BRAND.textMuted, marginTop: 6 }}>
        占比基于待开始、进行中和已完成工单 {total > 0 ? '' : '（暂无数据）'}
      </div>
    </Card>
  );
}
