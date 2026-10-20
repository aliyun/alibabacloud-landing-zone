import { useId, useState } from 'react';
import { RightOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { workspaceDomainLabel } from '@/shared/auth/access';
import type { WorkspaceDomain } from '@/shared/types/common';
import './MemberAccessModal.css';

const DOMAIN_GROUPS = [
  { key: 'delivery', domains: ['WORKITEM', 'SCHEDULEDTASK', 'DISPATCH', 'ARTIFACT', 'CLARIFICATION', 'CONVERSATION', 'DEBUGLOG'] },
  { key: 'workers', domains: ['AGENT', 'SQUAD', 'EXECUTOR'] },
  { key: 'knowledge', domains: ['REPO', 'MEMORY', 'SKILL', 'CATEGORY'] },
  { key: 'configuration', domains: ['SDLC', 'TEMPLATE', 'STATEMACHINE', 'INTEGRATION', 'WORKSPACE', 'BACKUP', 'ENVIRONMENT'] },
  { key: 'insights', domains: ['DASHBOARD', 'INSIGHTS', 'AUDIT'] },
] as const;
const ADVANCED_DOMAINS = ['AI', 'AIUSAGE', 'SETTING', 'EVOLUTION'] as const;
const ADVANCED_GROUP_KEY = 'advanced';
type ConfigurableDomain = Exclude<WorkspaceDomain, 'AUTH' | 'USER' | 'MCP'>;

export function ReadOnlyFeatures({ value = [], onChange, id, disabled = false }: {
  value?: WorkspaceDomain[];
  onChange?: (value: WorkspaceDomain[]) => void;
  id?: string;
  disabled?: boolean;
}) {
  const { t } = useTranslation('settings');
  const instanceId = useId();
  const [expandedGroups, setExpandedGroups] = useState<string[]>([]);

  const setDomainPermission = (domain: WorkspaceDomain, readOnly: boolean) => {
    onChange?.(readOnly
      ? value.includes(domain) ? value : [...value, domain]
      : value.filter((selected) => selected !== domain));
  };
  const setGroupPermission = (domains: readonly ConfigurableDomain[], readOnly: boolean) => {
    onChange?.(readOnly
      ? [...value, ...domains.filter((domain) => !value.includes(domain))]
      : value.filter((domain) => !domains.some((item) => item === domain)));
  };
  const renderDomain = (domain: ConfigurableDomain) => {
    const label = workspaceDomainLabel(domain);
    const readOnly = value.includes(domain);
    const descriptionId = `${instanceId}-${domain}-description`;
    return <div className="member-permission-row" key={domain}>
      <div className="member-permission-info">
        <div className="member-permission-name">{label}</div>
        <div className="member-domain-description" id={descriptionId}>{t(`readOnlyFeatures.description.${domain}`)}</div>
      </div>
      <fieldset className="member-permission-options" aria-describedby={descriptionId}>
        <legend className="member-permission-sr-only">{label}</legend>
        <label className="member-permission-choice">
          <input type="radio" name={`${instanceId}-${domain}`} checked={readOnly} disabled={disabled} onChange={() => setDomainPermission(domain, true)} />
          {t('readOnlyFeatures.readOnly')}
        </label>
        <label className="member-permission-choice">
          <input type="radio" name={`${instanceId}-${domain}`} checked={!readOnly} disabled={disabled} onChange={() => setDomainPermission(domain, false)} />
          {t('readOnlyFeatures.readWrite')}
        </label>
      </fieldset>
    </div>;
  };
  const renderGroup = (key: string, label: string, domains: readonly ConfigurableDomain[], advanced = false) => {
    const expanded = expandedGroups.includes(key);
    const readOnlyCount = domains.filter((domain) => value.includes(domain)).length;
    const panelId = `${instanceId}-${key}-panel`;
    return <section className="member-permission-group" key={key} aria-label={label}>
      <div className="member-permission-group-header">
        <button type="button" className="member-permission-group-toggle" aria-expanded={expanded} aria-controls={panelId}
          onClick={() => setExpandedGroups((current) => expanded ? current.filter((item) => item !== key) : [...current, key])}>
          <RightOutlined rotate={expanded ? 90 : 0} aria-hidden="true" />
          <span>{label}</span>
          <span className="member-permission-group-count">{t('readOnlyFeatures.groupStatus', { readOnly: readOnlyCount, readWrite: domains.length - readOnlyCount })}</span>
        </button>
        <div className="member-permission-group-actions">
          <span>{t('readOnlyFeatures.bulkLabel')}</span>
          <button type="button" disabled={disabled} aria-pressed={readOnlyCount === domains.length}
            aria-label={t('readOnlyFeatures.setGroupPermission', { group: label, permission: t('readOnlyFeatures.readOnly') })}
            onClick={() => setGroupPermission(domains, true)}>{t('readOnlyFeatures.bulkReadOnly')}</button>
          <button type="button" disabled={disabled} aria-pressed={readOnlyCount === 0}
            aria-label={t('readOnlyFeatures.setGroupPermission', { group: label, permission: t('readOnlyFeatures.readWrite') })}
            onClick={() => setGroupPermission(domains, false)}>{t('readOnlyFeatures.bulkReadWrite')}</button>
        </div>
      </div>
      <div className="member-permission-group-body" id={panelId} hidden={!expanded}>
        {advanced && <p className="member-domain-hint">{t('readOnlyFeatures.advancedHint')}</p>}
        {domains.map(renderDomain)}
      </div>
    </section>;
  };

  return <div className="member-permissions" id={id}>
    <p className="member-domain-hint">{t('readOnlyDomainsHint')}</p>
    {DOMAIN_GROUPS.map((group) => renderGroup(group.key, t(`readOnlyFeatures.group.${group.key}`), group.domains))}
    {renderGroup(ADVANCED_GROUP_KEY, t('readOnlyFeatures.advanced'), ADVANCED_DOMAINS, true)}
  </div>;
}
