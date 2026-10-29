import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { LockOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import { formatCredits } from '@/shared/lib/tokenFormat';
import type { CostComposition, CostCompositionSource } from '../realtime/api';
import FlashValue from '../realtime/FlashValue';

// 后端 key 为稳定枚举，用于本地化与配色；未知 key 回落到服务端返回的展示名。
const SOURCE_LABEL_KEYS = {
  WORKITEM_EXECUTION: 'cost.source.dispatch',
  REQUIREMENT_CLARIFY: 'cost.source.clarification',
  CHIEF_OF_STAFF: 'cost.source.platformAssistant',
  IM: 'cost.source.im',
  OTHER_CONVERSATION: 'cost.source.otherConversation',
} as const;

const SOURCE_COLORS: Record<keyof typeof SOURCE_LABEL_KEYS, string> = {
  WORKITEM_EXECUTION: 'var(--aw-info, #5b9cf6)',
  REQUIREMENT_CLARIFY: 'var(--aw-warning, #f5b83d)',
  CHIEF_OF_STAFF: 'var(--aw-success, #57c98b)',
  IM: 'var(--insight-human, #6bc7bb)',
  OTHER_CONVERSATION: 'var(--aw-muted, #a3adb9)',
};

function sourceLabel(t: TFunction<'insights'>, source: CostCompositionSource): string {
  const labelKey = SOURCE_LABEL_KEYS[source.key as keyof typeof SOURCE_LABEL_KEYS];
  return labelKey ? t(labelKey) : source.name;
}

function sourceColor(source: CostCompositionSource): string {
  return SOURCE_COLORS[source.key as keyof typeof SOURCE_COLORS] ?? 'var(--aw-muted, #a3adb9)';
}

function formatShare(share: number): string {
  return `${(share * 100).toFixed(1)}%`;
}

export interface InsightsCostCompositionProps {
  composition: CostComposition | null;
  /** 当前筛选范围文案，如「近30天」；chip 始终展示当前所选日期。 */
  rangeLabel: string;
  /** 上方选中具体员工时展示带锁口径。 */
  locked: boolean;
}

/** 成本分析面板的紧凑构成条：100% 堆叠条 + 图例，数据恒定按全员口径。 */
export function InsightsCostComposition({ composition, rangeLabel, locked }: InsightsCostCompositionProps) {
  const { t } = useTranslation('insights');
  const sources = composition?.sources ?? [];
  const total = composition?.total ?? 0;
  const empty = sources.length === 0;
  const chip = (
    <span className={`cost-composition-chip${locked ? ' is-locked' : ''}`} data-testid="cost-composition-chip">
      {locked && <LockOutlined aria-hidden="true" />}
      <span>{locked ? t('cost.compositionLockedChip') : t('cost.compositionAllChip', { range: rangeLabel })}</span>
    </span>
  );

  return (
    <section className="cost-composition" aria-label={t('cost.compositionAria')}>
      <div className="cost-composition-head">
        <h3>{t('cost.sourceChartTitle')}</h3>
        <Tooltip title={t('cost.compositionChipTooltip')}>{chip}</Tooltip>
        <span className="cost-composition-total">
          {t('cost.compositionTotal')} <b><FlashValue value={formatCredits(total)} /></b>
        </span>
      </div>
      {empty ? (
        <p className="cost-composition-empty">{t('cost.sourceChartEmpty')}</p>
      ) : (
        <>
          <div
            className="cost-composition-bar"
            data-testid="cost-composition-bar"
            role="img"
            aria-label={t('cost.compositionBarAria')}
          >
            {sources.map(source => (
              <i
                key={source.key}
                data-source={source.key}
                style={{ width: `${source.share * 100}%`, background: sourceColor(source) }}
                title={t('cost.sourceTooltip', {
                  name: sourceLabel(t, source),
                  amount: formatCredits(source.credits),
                  percent: formatShare(source.share),
                })}
              />
            ))}
          </div>
          <ul className="cost-composition-legend" aria-label={t('cost.sourceLegendAria')}>
            {sources.map(source => (
              <li key={source.key}>
                <i style={{ background: sourceColor(source) }} aria-hidden="true" />
                <span>{sourceLabel(t, source)}</span>
                <b><FlashValue value={formatCredits(source.credits)} /></b>
                <span className="cost-composition-share"><FlashValue value={formatShare(source.share)} /></span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

export default InsightsCostComposition;
