import { Popover, Tooltip, Typography } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import type { UsageSummary } from '@/shared/types/workitem';
import { formatCredits, formatTokenCount, formatWithCommas } from '@/shared/lib/tokenFormat';

const { Text } = Typography;

type PopoverTone = 'light' | 'dark';

function totalTokens(usage: UsageSummary): number {
  return (usage.inputTokens ?? 0) + (usage.outputTokens ?? 0);
}

function UsageLabel({ tone, children }: { tone: PopoverTone; children: ReactNode }) {
  return (
    <span style={{ fontWeight: 600, color: tone === 'dark' ? 'rgba(255, 255, 255, 0.85)' : undefined }}>
      {children}
    </span>
  );
}

interface TokenUsagePopoverContentProps {
  usage: UsageSummary;
  showModel?: boolean;
  tone?: PopoverTone;
}

function TokenUsagePopoverContent({ usage, showModel = true, tone = 'light' }: TokenUsagePopoverContentProps) {
  const input = usage.inputTokens ?? 0;
  const output = usage.outputTokens ?? 0;
  const cached = usage.cacheReadTokens ?? 0;
  const reasoning = usage.reasoningTokens ?? 0;
  const cacheHitRate = input > 0 ? ((cached / input) * 100).toFixed(1) : '0.0';

  return (
    <div style={{ minWidth: 200, lineHeight: '1.8', color: tone === 'dark' ? 'rgba(255, 255, 255, 0.95)' : undefined }}>
      {showModel && usage.model && (
        <div><UsageLabel tone={tone}>模型:</UsageLabel> {usage.model}</div>
      )}
      <div><UsageLabel tone={tone}>Total tokens:</UsageLabel> {formatWithCommas(totalTokens(usage))}</div>
      <div><UsageLabel tone={tone}>Input tokens:</UsageLabel> {formatWithCommas(input)}</div>
      <div><UsageLabel tone={tone}>Output tokens:</UsageLabel> {formatWithCommas(output)}</div>
      <div><UsageLabel tone={tone}>Cached tokens:</UsageLabel> {formatWithCommas(cached)}</div>
      {reasoning > 0 && (
        <div><UsageLabel tone={tone}>Reasoning tokens:</UsageLabel> {formatWithCommas(reasoning)}</div>
      )}
      <div><UsageLabel tone={tone}>Cache hit rate:</UsageLabel> {cacheHitRate}%</div>
      <div><UsageLabel tone={tone}>Credits:</UsageLabel> {formatCredits(usage.credits)}</div>
    </div>
  );
}

interface TokenUsageBadgeProps {
  usage: UsageSummary;
  showModel?: boolean;
}

export function TokenUsageBadge({ usage, showModel = true }: TokenUsageBadgeProps) {
  if (totalTokens(usage) <= 0) return null;

  return (
    <Popover content={<TokenUsagePopoverContent usage={usage} showModel={showModel} />} trigger="hover">
      <Text type="secondary" style={{ fontSize: 12, cursor: 'pointer', whiteSpace: 'nowrap', flexShrink: 0 }}>
        <ThunderboltOutlined style={{ marginRight: 2 }} />
        {formatTokenCount(totalTokens(usage))}
      </Text>
    </Popover>
  );
}

interface StepTokenBadgeProps {
  usage: UsageSummary;
}

export function StepTokenBadge({ usage }: StepTokenBadgeProps) {
  if (totalTokens(usage) <= 0) return null;

  return (
    <Tooltip title={<TokenUsagePopoverContent usage={usage} showModel={false} tone="dark" />}>
      <Text type="secondary" style={{ fontSize: 11, cursor: 'pointer', whiteSpace: 'nowrap' }}>
        <ThunderboltOutlined style={{ marginRight: 2 }} />
        {formatTokenCount(totalTokens(usage))}
      </Text>
    </Tooltip>
  );
}
