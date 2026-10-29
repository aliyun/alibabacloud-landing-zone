import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, DatePicker, Input, Select } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import type { Agent } from '@/features/agent/api';
import type { AuditFilterState } from './auditFilters';
import { isAuditActorType, normalizeRange } from './auditFilters';
import { ACTION_KEYS, ACTOR_KEYS, MODULE_KEYS, RISK_KEYS, TARGET_KEYS } from './auditLabels';

const { RangePicker } = DatePicker;

const ACTOR_VALUES = ['HUMAN', 'AGENT', 'SYSTEM'] as const;
const MODULE_VALUES = ['WORKITEM', 'AGENT', 'SKILL', 'STATUS_TEMPLATE', 'SCHEDULED_TASK'] as const;
const ACTION_VALUES = ['CREATE', 'UPDATE', 'DELETE', 'APPROVE', 'REJECT', 'RETRY', 'FORCE_PUBLISH', 'ROLE_CHANGE'] as const;
const RISK_VALUES = ['high', 'medium', 'low'] as const;
const TARGET_VALUES = ['workitem', 'agent', 'skill', 'scheduled_task_run'] as const;
const PRESET_DAYS = [7, 30, 90, 180] as const;

interface AuditLogFilterBarProps {
  value: AuditFilterState;
  keywordInput: string;
  agents: Agent[];
  onChange: (next: AuditFilterState) => void;
  onKeywordChange: (keyword: string) => void;
  onReset: () => void;
}

export function AuditLogFilterBar({ value, keywordInput, agents, onChange, onKeywordChange, onReset }: AuditLogFilterBarProps) {
  const { t } = useTranslation('audit');
  const activeClass = (active: boolean) => (active ? 'audit-filter is-active' : 'audit-filter');
  const showAgentFilter = value.actorType === 'AGENT';
  const rangePickerRef = useRef<HTMLSpanElement>(null);
  const [rangeOpen, setRangeOpen] = useState(false);

  // antd RangePicker 不输出 dialog/aria-expanded，清除控件也无名称，按 demo 补齐无障碍属性。
  useEffect(() => {
    const picker = rangePickerRef.current?.querySelector('.ant-picker');
    if (!picker) {
      return;
    }
    picker.querySelectorAll('input').forEach((input) => input.setAttribute('aria-expanded', String(rangeOpen)));
    const clear = picker.querySelector('.ant-picker-clear');
    if (clear) {
      clear.setAttribute('role', 'button');
      clear.setAttribute('aria-label', t('clearTimeRange'));
      clear.setAttribute('title', t('clearTimeRange'));
    }
  }, [rangeOpen, value.range, t]);

  const handleActorType = (actorType: string) => {
    const next = isAuditActorType(actorType) ? actorType : '';
    onChange({ ...value, actorType: next, actorId: next === 'AGENT' ? value.actorId : undefined });
  };

  const handleRange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    onChange({ ...value, range: normalizeRange(dates) });
  };

  return (
    <div className="audit-filter-bar">
      <Select
        aria-label={t('actorType')}
        className={activeClass(value.actorType !== '')}
        value={value.actorType}
        style={{ width: 140 }}
        options={[
          { value: '', label: t('allActors') },
          ...ACTOR_VALUES.map((actor) => ({ value: actor, label: t(ACTOR_KEYS[actor]) })),
        ]}
        onChange={(actorType: string) => handleActorType(actorType)}
      />
      {showAgentFilter && (
        <Select
          aria-label={t('agent')}
          className={activeClass(value.actorId != null)}
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder={t('allActors')}
          value={value.actorId}
          style={{ width: 180 }}
          options={agents.map((agent) => ({ value: agent.id, label: agent.name }))}
          onChange={(actorId?: number) => onChange({ ...value, actorId })}
        />
      )}
      <Select
        aria-label={t('module')}
        className={activeClass(value.module !== '')}
        value={value.module}
        style={{ width: 140 }}
        options={[
          { value: '', label: t('allModules') },
          ...MODULE_VALUES.map((module) => ({ value: module, label: t(MODULE_KEYS[module]) })),
        ]}
        onChange={(module: string) => onChange({ ...value, module })}
      />
      <Select
        aria-label={t('action')}
        className={activeClass(value.action !== '')}
        value={value.action}
        style={{ width: 150 }}
        options={[
          { value: '', label: t('allActions') },
          ...ACTION_VALUES.map((action) => ({ value: action, label: t(ACTION_KEYS[action]) })),
        ]}
        onChange={(action: string) => onChange({ ...value, action })}
      />
      <Select
        aria-label={t('risk')}
        className={activeClass(value.riskLevel !== '')}
        value={value.riskLevel}
        style={{ width: 130 }}
        options={[
          { value: '', label: t('allRisks') },
          ...RISK_VALUES.map((risk) => ({ value: risk, label: t(RISK_KEYS[risk]) })),
        ]}
        onChange={(riskLevel: string) => onChange({ ...value, riskLevel })}
      />
      <Select
        aria-label={t('targetType')}
        className={activeClass(value.targetType !== '')}
        value={value.targetType}
        style={{ width: 170 }}
        options={[
          { value: '', label: t('allTargetTypes') },
          ...TARGET_VALUES.map((targetType) => ({ value: targetType, label: t(TARGET_KEYS[targetType]) })),
        ]}
        onChange={(targetType: string) => onChange({ ...value, targetType })}
      />
      <span ref={rangePickerRef} style={{ display: 'contents' }}>
        <RangePicker
          aria-label={t('timeRange')}
          className={activeClass(value.range !== null)}
          allowClear
          placeholder={[t('rangeStart'), t('rangeEnd')]}
          presets={PRESET_DAYS.map((days) => ({
            label: t('lastDays', { count: days }),
            value: [dayjs().subtract(days - 1, 'day'), dayjs()] as [Dayjs, Dayjs],
          }))}
          panelRender={(panel) => (
            <div role="dialog" aria-label={t('timeRangeDialog')}>{panel}</div>
          )}
          onOpenChange={setRangeOpen}
          disabledDate={(current) => current != null && current > dayjs().endOf('day')}
          value={value.range}
          onChange={handleRange}
        />
      </span>
      <Input
        aria-label={t('keywords')}
        className={activeClass(keywordInput.trim() !== '')}
        allowClear
        placeholder={t('keywordPlaceholder')}
        value={keywordInput}
        style={{ width: 220 }}
        onChange={(event) => onKeywordChange(event.target.value)}
      />
      <Button className="audit-filter-reset" onClick={onReset}>{t('resetFilters')}</Button>
    </div>
  );
}
