import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { Popover, Typography } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import type { WorkitemUsageRun, WorkitemUsageSummary } from '@/shared/types/workitem';
import { formatCredits, formatCreditsFixed } from '@/shared/lib/tokenFormat';

const { Text } = Typography;

function runLabel(run: WorkitemUsageRun, index: number): string {
  if (run.labelKind === 'AGENT_RUN') {
    // Match Java isBlank/trim used by the owned producer; authored whitespace stays raw in the DTO.
    // eslint-disable-next-line no-control-regex -- Java isBlank intentionally recognizes these control whitespace characters.
    const nonblank = run.agentName != null && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/.test(run.agentName);
    // eslint-disable-next-line no-control-regex -- Java trim removes exactly U+0000 through U+0020, unlike JavaScript trim.
    const name = nonblank ? run.agentName!.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
      : run.agentId != null ? i18n.t('workitem:usage.agentFallback', { id: run.agentId }) : i18n.t('workitem:delivery.unknown');
    return i18n.t('workitem:usage.runLabel', { name, number: run.runIndex ?? index + 1 });
  }
  if (run.label) return run.label;
  const name = run.agentName || (run.agentId != null ? i18n.t('workitem:usage.agentFallback', { id: run.agentId }) : i18n.t('workitem:delivery.unknown'));
  return i18n.t('workitem:usage.runLabel', { name, number: run.runIndex ?? index + 1 });
}

function CreditsBreakdown({ usage }: { usage: WorkitemUsageSummary }) {
  const { t } = useTranslation('workitem');
  const runs = (usage.runs ?? [])
    .map((run, sourceIndex) => ({ run, sourceIndex }))
    .filter(({ run }) => (run.credits ?? 0) > 0)
    .sort((a, b) => (a.run.runIndex ?? Number.POSITIVE_INFINITY) - (b.run.runIndex ?? Number.POSITIVE_INFINITY) || a.sourceIndex - b.sourceIndex);
  return (
    <div data-testid="workitem-credits-breakdown" style={{ minWidth: 200, lineHeight: '1.8' }}>
      {runs.map(({ run, sourceIndex }, index) => (
        <div key={`${run.agentId ?? 'unknown'}-${run.runIndex ?? index + 1}-${sourceIndex}`}>
          <span style={{ fontWeight: 600 }}>{runLabel(run, index)}:</span> {t('usage.creditAmount', { amount: formatCredits(run.credits) })}
        </div>
      ))}
      <div>
        <span style={{ fontWeight: 600 }}>{t('usage.total')}</span> {t('usage.totalAmount', { amount: formatCreditsFixed(usage.credits) })}
      </div>
    </div>
  );
}

interface WorkitemCreditsBadgeProps {
  usage?: WorkitemUsageSummary | null;
}

/** 工单详情页顶部的累计 credits 徽标，hover 展开按执行轮次拆分的费用清单。 */
export function WorkitemCreditsBadge({ usage }: WorkitemCreditsBadgeProps) {
  useTranslation('workitem');
  const credits = usage?.credits ?? 0;
  if (!usage || !(credits > 0)) return null;

  return (
    <Popover content={<CreditsBreakdown usage={usage} />} trigger="hover">
      <Text
        data-testid="workitem-credits-badge"
        type="secondary"
        style={{ fontSize: 13, cursor: 'pointer', whiteSpace: 'nowrap' }}
      >
        <ThunderboltOutlined aria-hidden="true" style={{ marginRight: 2 }} />
        {i18n.t('workitem:usage.totalAmount', { amount: formatCreditsFixed(credits) })}
      </Text>
    </Popover>
  );
}
