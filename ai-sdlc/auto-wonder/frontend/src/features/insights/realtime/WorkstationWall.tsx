import { useState } from 'react';
import type { Workstation } from './api';
import { useAgentRunning } from './hooks';
import { BRAND, cardStyle } from './theme';

interface Props {
  workstations: Workstation[];
}

export default function WorkstationWall({ workstations }: Props) {
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const { data: runningTasks, isLoading } = useAgentRunning(expandedId);

  return (
    <div style={cardStyle}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 12 }}>
        数字人工位墙{' '}
        <span style={{ color: BRAND.textMuted, fontWeight: 400 }}>· 点击展开正在执行的任务</span>
      </div>
      {workstations.length === 0 ? (
        <div style={{ color: BRAND.textMuted, fontSize: 12 }}>暂无在岗数字人</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
          {workstations.map((w) => {
            const selected = expandedId === w.agentId;
            return (
              <div
                key={w.agentId}
                onClick={() => setExpandedId(selected ? null : w.agentId)}
                style={{
                  cursor: 'pointer',
                  background: w.busy ? BRAND.orangeBg : '#fafafa',
                  border: `1px solid ${w.busy ? BRAND.orangeBorder : BRAND.cardBorder}`,
                  outline: selected ? `2px solid ${BRAND.orange}` : 'none',
                  borderRadius: 8,
                  padding: 10,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                  <div
                    style={{
                      width: 26,
                      height: 26,
                      borderRadius: '50%',
                      background: w.busy ? BRAND.orange : BRAND.grey,
                      backgroundImage: w.avatarUrl ? `url(${w.avatarUrl})` : undefined,
                      backgroundSize: 'cover',
                    }}
                  />
                  <div style={{ fontSize: 11, fontWeight: 600, color: w.busy ? '#1f2937' : '#8c8c8c' }}>
                    {w.name}
                  </div>
                </div>
                <div style={{ fontSize: 10, fontWeight: 600, color: w.busy ? BRAND.orange : '#bfbfbf' }}>
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
            fontSize: 11,
            color: '#595959',
          }}
        >
          {isLoading && <div style={{ color: BRAND.textMuted }}>加载中…</div>}
          {!isLoading && (runningTasks?.length ?? 0) === 0 && (
            <div style={{ color: BRAND.textMuted }}>该数字人当前没有正在执行的任务</div>
          )}
          {!isLoading &&
            runningTasks?.map((t) => (
              <div key={t.dispatchId} style={{ lineHeight: 2 }}>
                <span style={{ color: BRAND.orange }}>▶</span> {t.workitemTitle ?? '未命名工单'}
                {t.stepName ? ` · ${t.stepName}` : ''} · 已跑 {t.runningMinutes}m
              </div>
            ))}
        </div>
      )}
    </div>
  );
}
