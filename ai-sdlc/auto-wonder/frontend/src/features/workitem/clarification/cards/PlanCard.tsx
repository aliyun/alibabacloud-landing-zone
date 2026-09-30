import { Spin, Typography } from 'antd';
import { CheckCircleOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import type { AcpPlanEntry, AcpPlanPriority, AcpPlanStatus } from '../types';
import { CLARIFICATION_THEME } from '../theme';

interface PlanCardProps {
  entries: AcpPlanEntry[];
}

/** 优先级与完成态是语义色，不走中性令牌。 */
const PRIORITY_COLOR: Record<AcpPlanPriority, string> = {
  high: 'var(--aw-error)',
  medium: 'var(--aw-warning)',
  low: CLARIFICATION_THEME.textMuted,
};

const STATUS_AFFORDANCE: Record<AcpPlanStatus, ReactNode> = {
  completed: (
    <CheckCircleOutlined
      data-testid="plan-entry-icon-completed"
      style={{ color: 'var(--aw-success)', marginTop: 2 }}
    />
  ),
  in_progress: (
    <Spin size="small" data-testid="plan-entry-icon-in_progress" style={{ marginTop: 2 }} />
  ),
  pending: (
    <span
      data-testid="plan-entry-icon-pending"
      style={{
        width: 12, height: 12, marginTop: 4, borderRadius: '50%',
        border: `1px solid ${CLARIFICATION_THEME.controlBorder}`,
        display: 'inline-block', flex: '0 0 auto',
      }}
    />
  ),
};

export function PlanCard({ entries }: PlanCardProps) {
  if (entries.length === 0) return null;

  return (
    <div
      data-testid="acp-plan-card"
      style={{
        marginBottom: 8, padding: '8px 12px',
        borderRadius: CLARIFICATION_THEME.radiusBlock,
        border: `1px solid ${CLARIFICATION_THEME.hairline}`,
        backgroundColor: CLARIFICATION_THEME.codeSurface,
      }}
    >
      <Typography.Text strong style={{ fontSize: 14, display: 'block', marginBottom: 6 }}>
        执行计划
      </Typography.Text>
      {entries.map((entry, index) => (
        <div
          key={index}
          data-testid={`plan-entry-${entry.status}-${index}`}
          style={{ display: 'flex', alignItems: 'flex-start', gap: 6, marginBottom: 4 }}
        >
          {STATUS_AFFORDANCE[entry.status]}
          <span
            style={{
              fontSize: 14,
              flex: 1,
              color: entry.status === 'completed'
                ? CLARIFICATION_THEME.textMuted
                : CLARIFICATION_THEME.textPrimary,
              textDecoration: entry.status === 'completed' ? 'line-through' : 'none',
            }}
          >
            {entry.content}
          </span>
          <span style={{ fontSize: 13, color: PRIORITY_COLOR[entry.priority] }}>
            {entry.priority}
          </span>
        </div>
      ))}
    </div>
  );
}
