import type { Inventory } from './api';
import { BRAND, cardStyle } from './theme';

interface Props {
  inventory: Inventory;
}

export default function InventoryPanel({ inventory }: Props) {
  const { byLifecycle, byType } = inventory;
  const init = byLifecycle.init;
  const inProgress = byLifecycle.inProgress;
  const done = byLifecycle.done;
  const total = Math.max(init + inProgress + done, 1);
  return (
    <div style={cardStyle}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 12 }}>工单存量</div>
      <div
        style={{
          display: 'flex',
          gap: 4,
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
      <div style={{ fontSize: 11, color: '#595959', lineHeight: 1.9 }}>
        <div>
          待开始 <b>{init}</b> · 进行中{' '}
          <b style={{ color: BRAND.orange }}>{inProgress}</b> · 已完成 <b>{done}</b>
          {byLifecycle.canceled > 0 && <> · 已取消 <b>{byLifecycle.canceled}</b></>}
        </div>
        <div style={{ marginTop: 8, color: BRAND.textMuted }}>
          需求 {byType.req} · 任务 {byType.task} · 缺陷 {byType.bug}
        </div>
      </div>
      <div style={{ fontSize: 9, color: BRAND.textMuted, marginTop: 6 }}>
        占比基于 待开始/进行中/已完成 {total > 1 ? '' : '（暂无数据）'}
      </div>
    </div>
  );
}
