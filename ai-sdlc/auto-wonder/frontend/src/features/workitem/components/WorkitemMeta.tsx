import { externalPrincipalName } from '../runtimeLabels';
import type { ExternalPrincipalNameFallback } from '@/shared/types/workitem';
import { Tag } from 'antd';
import { getPriorityMeta, workTypeMap } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';
import { Trans, useTranslation } from 'react-i18next';

interface WorkitemMetaProps {
  workType: string;
  statusName: string | null;
  priority: number;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeNameFallback?: ExternalPrincipalNameFallback | null;
  assigneeType: string;
  creatorDisplayName?: string | null;
  sdlcName: string | null;
  tags?: string[];
}

export function WorkitemMeta({ workType, statusName, priority, assigneeName, assigneeDisplayName, assigneeNameFallback, assigneeType, creatorDisplayName, sdlcName, tags }: WorkitemMetaProps) {
  const { t } = useTranslation('workitem');
  const priorityMeta = getPriorityMeta(priority);
  const assigneeText = externalPrincipalName(displayNameWithoutId(assigneeDisplayName, assigneeName), assigneeNameFallback);
  const isAgent = assigneeType === 'AGENT';

  return (
    <div className="aw-workitem-meta">
      <span>
        <Trans t={t} i18nKey="meta.type" components={{ value: <Tag style={{ margin: 0 }}>{workTypeMap[workType]?.label ?? workType}</Tag> }} />
      </span>
      <span>
        <Trans t={t} i18nKey="meta.status" components={{ value: <Tag style={{ margin: 0, color: 'var(--aw-accent-text)', borderColor: 'var(--aw-accent)', background: 'rgba(var(--aw-accent-rgb),.10)' }}>{statusName ?? t('status.unknown')}</Tag> }} />
      </span>
      <span>
        <Trans t={t} i18nKey="meta.priority" components={{ value: <Tag color={priorityMeta.color} style={{ margin: 0 }}>{priorityMeta.label}</Tag> }} />
      </span>
      {creatorDisplayName && (
        <span><Trans t={t} i18nKey="meta.creator" components={{ value: <span>{displayNameWithoutId(creatorDisplayName)}</span> }} /></span>
      )}
      <span>
        <Trans t={t} i18nKey="meta.assignee" components={{ value: <span>{isAgent && <Tag color="purple" style={{ margin: 0 }}>AI</Tag>}{isAgent ? ' ' : ''}{assigneeText ?? t('assignee.unassigned')}</span> }} />
      </span>
      {sdlcName && (
        <span><Trans t={t} i18nKey="meta.sdlc" components={{ value: <span>{sdlcName}</span> }} /></span>
      )}
      {tags && tags.length > 0 && (
        <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center', flexWrap: 'wrap', minWidth: 0 }}>
          <Trans t={t} i18nKey="meta.tags" components={{ value: <span>{tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</span> }} />
        </span>
      )}
    </div>
  );
}
