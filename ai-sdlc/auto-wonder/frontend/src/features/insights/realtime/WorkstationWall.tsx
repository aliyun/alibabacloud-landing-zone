import { Alert, Button, Card } from 'antd';
import { useState } from 'react';
import type { Workstation } from './api';
import { useAgentRunning } from './hooks';
import { formatMinutesZh } from '@/shared/lib/duration';
import { BRAND } from './theme';

interface Props {
  workstations: Workstation[];
}

export default function WorkstationWall({ workstations }: Props) {
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const { data: runningTasks, isLoading, isError, refetch } = useAgentRunning(expandedId);

  return (
    <Card title={<>数字员工工位墙{' '}
        <span style={{ color: BRAND.textMuted, fontWeight: 400, fontSize: 12 }}>· 点击展开正在执行的任务</span></>} style={{ minWidth: 0 }}>
      {workstations.length === 0 ? (
        <div style={{ color: BRAND.textMuted, fontSize: 12, textAlign: 'center' }}>暂无在岗数字员工</div>
      ) : (
        <div className="insight-workstation-grid">
          {workstations.map((w) => {
            const selected = expandedId === w.agentId;
            return (
              <div
                key={w.agentId}
                className="insight-workstation"
                role="button"
                tabIndex={0}
                aria-expanded={selected}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setExpandedId(selected ? null : w.agentId);
                  }
                }}
                onClick={() => setExpandedId(selected ? null : w.agentId)}
                style={{
                  cursor: 'pointer',
                  textAlign: 'center',
                  background: 'var(--aw-raised)',
                  border: `1px solid ${w.busy ? BRAND.orangeBorder : BRAND.cardBorder}`,
                  boxShadow: selected ? `0 0 0 2px ${BRAND.orange}` : undefined,
                  borderRadius: 8,
                  padding: 10,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-start', textAlign: 'left', gap: 8, marginBottom: 6 }}>
                  <div
                    style={{
                      width: 26,
                      flexShrink: 0,
                      height: 26,
                      display: 'grid',
                      placeItems: 'center',
                      borderRadius: '50%',
                      background: 'color-mix(in srgb, var(--aw-accent) 20%, var(--aw-raised))',
                      backgroundImage: w.avatarUrl ? `url(${w.avatarUrl})` : undefined,
                      backgroundSize: 'cover',
                      backgroundPosition: 'center',
                      color: 'var(--aw-accent-text)',
                      fontSize: 12,
                      fontWeight: 700,
                    }}
                  >{!w.avatarUrl && Array.from(w.name || '?')[0]}</div>
                  <div className="insight-truncate" title={w.name} style={{ fontSize: 12, fontWeight: 600, color: w.busy ? 'var(--aw-text)' : 'var(--aw-muted)' }}>
                    {w.name}
                  </div>
                </div>
                <div style={{ fontSize: 12, fontWeight: 600, color: w.busy ? BRAND.orange : 'var(--aw-muted)' }}>
                  {w.busy ? `● 忙碌 · ${w.runningTasks} 任务` : '○ 空闲'}
                </div>
              </div>
            );
          })}
        </div>
      )}
      {expandedId != null && (
        <div
          style={{
            marginTop: 12,
            borderTop: `1px dashed ${BRAND.cardBorder}`,
            paddingTop: 10,
            fontSize: 12,
            color: 'var(--aw-muted)',
          }}
        >
          {isLoading && <div style={{ color: BRAND.textMuted }}>加载中…</div>}
          {isError && <Alert type="error" showIcon message="任务加载失败" action={<Button size="small" onClick={() => { void refetch(); }}>重试</Button>} />}
          {!isLoading && !isError && (runningTasks?.length ?? 0) === 0 && (
            <div style={{ color: BRAND.textMuted }}>该数字员工当前没有正在执行的任务</div>
          )}
          {!isLoading && !isError &&
            runningTasks?.map((t) => (
              <div key={t.dispatchId} style={{ lineHeight: 2 }}>
                <span style={{ color: BRAND.orange }}>▶</span> {t.workitemTitle ?? '未命名工单'}
                {t.stepName ? ` · ${t.stepName}` : ''} · 已运行 <span className="insight-realtime-metric">{formatMinutesZh(t.runningMinutes)}</span>
              </div>
            ))}
        </div>
      )}
    </Card>
  );
}
