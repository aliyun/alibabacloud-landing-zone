import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Switch } from 'antd';
import type { AgentVersion } from '../../api';
import { getAgentRoleLabel, getEvolutionModeLabel } from '../../constants';
import { evolutionModeOf, type ConfigFieldDiff, type ConfigFieldKey } from './buildVersionDiff';

interface FieldDiffTableProps {
  fields: ConfigFieldDiff[];
  base: AgentVersion | null;
  target: AgentVersion;
  firstVersion: boolean;
  sdlcNameOf?: (sdlcId: number) => string | undefined;
}

const FIELD_LABEL_KEY = {
  roleName: 'fields.roleName',
  roleCode: 'fields.roleCode',
  sdlc: 'fields.sdlc',
  evolution: 'fields.evolution',
} as const;

function displayValue(
  key: ConfigFieldKey,
  version: AgentVersion,
  sdlcNameOf?: (sdlcId: number) => string | undefined,
): string {
  switch (key) {
    case 'roleName': {
      const label = getAgentRoleLabel(version.roleCode, version.roleName);
      return label || '-';
    }
    case 'roleCode':
      return version.roleCode || '-';
    case 'sdlc':
      if (version.sdlcId == null) return '-';
      return sdlcNameOf?.(version.sdlcId) || `#${version.sdlcId}`;
    case 'evolution':
      return getEvolutionModeLabel(evolutionModeOf(version));
    default:
      return '-';
  }
}

export function FieldDiffTable({ fields, base, target, firstVersion, sdlcNameOf }: FieldDiffTableProps) {
  const { t } = useTranslation('agent');
  const [showUnchanged, setShowUnchanged] = useState(false);
  const visible = fields
    .filter((field) => field.changed || showUnchanged)
    .map((field) => {
      const targetValue = displayValue(field.key, target, sdlcNameOf);
      const baseValue = base ? displayValue(field.key, base, sdlcNameOf) : '-';
      const changed = field.changed;
      return {
        key: field.key,
        changed,
        baseText: changed ? `− ${baseValue}` : baseValue,
        targetText: changed ? `+ ${targetValue}` : targetValue,
      };
    });

  return (
    <div>
      <div className="awdiff-toolbar">
        <label className="awdiff-switch">
          <Switch size="small" checked={showUnchanged} onChange={setShowUnchanged} aria-label={t('diff.action.showUnchanged')} />
          <span>{t('diff.action.showUnchanged')}</span>
        </label>
      </div>
      {firstVersion && (
        <div className="awdiff-empty">{t('diff.notice.firstVersionConfig')}</div>
      )}
      {visible.length === 0 ? (
        <div className="awdiff-empty">{t('diff.group.noChange')}</div>
      ) : (
        <table className="awdiff-field-table" data-testid="awdiff-field-table">
          <thead>
            <tr>
              <th className="awdiff-field-name">{t('diff.column.field')}</th>
              {!firstVersion && (
                <th className="awdiff-field-old">{t('diff.column.baseWithVersion', { version: base?.versionNo ?? '' })}</th>
              )}
              <th className="awdiff-field-new">{t('diff.column.targetWithVersion', { version: target.versionNo })}</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((row) => (
              <tr key={row.key} className={row.changed ? 'awdiff-field-row awdiff-field-changed' : 'awdiff-field-row'}>
                <td className="awdiff-field-name">{t(FIELD_LABEL_KEY[row.key])}</td>
                {!firstVersion && (
                  <td className="awdiff-field-old" data-label={t('diff.column.base')}>
                    <span className="awdiff-field-val">{row.baseText}</span>
                  </td>
                )}
                <td className="awdiff-field-new" data-label={t('diff.column.target')}>
                  <span className="awdiff-field-val">{row.targetText}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
