import { useTranslation } from 'react-i18next';
import { Alert, Space, Typography } from 'antd';

export interface IdentityConflictWorkspace {
  workspaceId: number;
  workspaceName: string;
}

export interface IdentityConflictView {
  reason?: string;
  workspaces?: IdentityConflictWorkspace[];
  restricted?: boolean;
}

export const IDENTITY_CONFLICT_REASONS = {
  LEGACY_BINDING_OCCUPIED: 'LEGACY_BINDING_OCCUPIED',
  NOTIFY_GROUP_IDENTITY_CONFLICT: 'NOTIFY_GROUP_IDENTITY_CONFLICT',
  SHARED_IDENTITY_OCCUPIED: 'SHARED_IDENTITY_OCCUPIED',
} as const;

/** Reads the structured conflict payload without matching on any localized message text. */
export function identityConflictData(error: unknown): IdentityConflictView | null {
  const data = (error as { data?: unknown } | null | undefined)?.data;
  if (!data || typeof data !== 'object') {
    return null;
  }
  const reason = (data as IdentityConflictView).reason;
  return typeof reason === 'string' ? (data as IdentityConflictView) : null;
}

/**
 * Opens the target Workspace's channel integration in a new tab. The workspace page performs the
 * membership check and the actual switch, so the current tab never writes into the wrong Workspace.
 */
export function workspaceChannelsUrl(workspaceId: number): string {
  const params = new URLSearchParams({
    targetWorkspaceId: String(workspaceId),
    returnTo: '/integrations?tab=channels',
  });
  return `/workspaces?${params.toString()}`;
}

interface Props {
  error: unknown;
}

export function ImIdentityConflictAlert({ error }: Props) {
  const { t } = useTranslation('integration');
  const conflict = identityConflictData(error);
  if (!conflict || !conflict.reason) {
    return null;
  }
  const workspaces = conflict.workspaces ?? [];
  if (conflict.reason === IDENTITY_CONFLICT_REASONS.LEGACY_BINDING_OCCUPIED) {
    const unavailable = workspaces.length === 0 && !conflict.restricted;
    return (
      <Alert
        type="warning"
        showIcon
        message={t('conflictTitle')}
        description={
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Typography.Text>{t('conflictLegacyBody')}</Typography.Text>
            {workspaces.length > 0 && (
              <Typography.Text>{t('conflictLegacyWorkspaces')}</Typography.Text>
            )}
            {workspaces.map((workspace) => (
              <Typography.Link
                key={workspace.workspaceId}
                href={workspaceChannelsUrl(workspace.workspaceId)}
                target="_blank"
                rel="noopener noreferrer"
              >
                <span>{workspace.workspaceName}</span>
                <span style={{ marginInlineStart: 6 }}>{t('openChannelsIntegration')}</span>
              </Typography.Link>
            ))}
            {conflict.restricted && (
              <Typography.Text type="secondary">{t('conflictLegacyRestricted')}</Typography.Text>
            )}
            {unavailable && (
              <Typography.Text type="secondary">{t('conflictLegacyUnavailable')}</Typography.Text>
            )}
            <Typography.Text type="secondary">{t('conflictLegacyDeleteImpact')}</Typography.Text>
          </Space>
        }
      />
    );
  }
  const message = conflict.reason === IDENTITY_CONFLICT_REASONS.NOTIFY_GROUP_IDENTITY_CONFLICT
    ? t('conflictNotify')
    : conflict.reason === IDENTITY_CONFLICT_REASONS.SHARED_IDENTITY_OCCUPIED
      ? t('conflictShared')
      : t('conflictUnknown');
  return <Alert type="warning" showIcon message={t('conflictTitle')} description={message} />;
}
