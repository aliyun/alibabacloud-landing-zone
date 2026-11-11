import { useEffect, useState, type ReactNode } from 'react';
import { Button, Modal, Tag } from 'antd';
import { DownOutlined, FullscreenOutlined, UpOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ActionPlan } from './api';
import './chiefActionPlan.css';

function formatJson(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}

function actionCount(actionsJson: string): number | null {
  try {
    const parsed: unknown = JSON.parse(actionsJson);
    return Array.isArray(parsed) ? parsed.length : null;
  } catch { return null; }
}

const defaultExpanded = (status: string) => status === 'PENDING' || status === 'EXECUTING' || status === 'FAILED';

export function ChiefActionPlan({ plan, children }: { plan: ActionPlan; children?: ReactNode }) {
  const { t } = useTranslation('chiefOfStaff');
  const [expanded, setExpanded] = useState(() => defaultExpanded(plan.status));
  const [detailOpen, setDetailOpen] = useState(false);
  useEffect(() => { setExpanded(defaultExpanded(plan.status)); }, [plan.status]);
  const status = t(plan.status === 'EXPIRED' ? 'planExpired' : plan.status === 'PENDING' ? 'planPending'
    : plan.status === 'COMPLETED' ? 'planCompleted' : plan.status === 'CANCELED' ? 'planCanceled'
      : plan.status === 'EXECUTING' ? 'planExecuting' : 'planFailed');
  const failed = plan.status === 'FAILED';
  const count = actionCount(plan.actionsJson);
  const toggleLabel = expanded ? t('collapsePlanDetail') : t('expandPlanDetail');
  const details = <>
    <h4>{t('planInput')}</h4><pre>{formatJson(plan.actionsJson)}</pre>
    {plan.resultJson && <><h4>{t('planOutput')}</h4><pre>{formatJson(plan.resultJson)}</pre></>}
  </>;
  return <section className={`aw-chief-plan${failed ? ' aw-chief-plan-failed' : ''}`}>
    <div className="aw-chief-plan-heading">
      <strong>{t('actionPlan')}</strong><Tag color={failed ? 'error' : undefined}>{status}</Tag>
      {!expanded && count !== null && <span className="aw-chief-plan-summary">{t('planActionsCount', { count })}</span>}
      <div className="aw-chief-plan-toggle-group">
        <Button type="text" size="small" className="aw-chief-plan-toggle" aria-expanded={expanded}
          icon={expanded ? <UpOutlined aria-hidden="true" /> : <DownOutlined aria-hidden="true" />}
          title={toggleLabel} aria-label={toggleLabel} onClick={() => setExpanded(current => !current)} />
        {expanded && <Button type="text" size="small" icon={<FullscreenOutlined />} title={t('expandPlan')}
          aria-label={t('expandPlan')} onClick={() => setDetailOpen(true)} />}
      </div>
    </div>
    {expanded && <div className="aw-chief-plan-preview" tabIndex={0} role="region" aria-label={t('actionPlan')}>{details}</div>}
    {children}
    <Modal title={<>{t('actionPlan')} <Tag color={failed ? 'error' : undefined}>{status}</Tag></>} open={detailOpen} onCancel={() => setDetailOpen(false)}
      footer={null} width="min(1000px, calc(100vw - 32px))" centered zIndex={1300}>
      <div className="aw-chief-plan-detail" tabIndex={0} role="region" aria-label={t('actionPlan')}>{details}</div>
    </Modal>
  </section>;
}
