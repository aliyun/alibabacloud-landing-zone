import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { Alert, Avatar, Card, Space, Tag, Typography } from 'antd';
import { LinkOutlined, UserOutlined } from '@ant-design/icons';
import type { ExternalCollaboration, ExternalPrincipal } from '@/shared/types/workitem';

const SYNC_TIME_FORMAT: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit' };

interface ExternalCollaborationCardProps {
  collaboration?: ExternalCollaboration | null;
}

const syncStatusLabel = {
  HEALTHY: 'collaboration.healthy',
  DELAYED: 'collaboration.delayed',
  ACTION_REQUIRED: 'collaboration.needsAttention',
} as const;

const syncStatusColor: Record<string, string> = {
  HEALTHY: 'success',
  DELAYED: 'warning',
  ACTION_REQUIRED: 'error',
};

const syncStatusAlertType: Record<string, 'warning' | 'error' | 'info' | undefined> = {
  ACTION_REQUIRED: 'warning',
};

const lifecycleLabel = {
  CLOSED: 'collaboration.closed',
  DELETED: 'collaboration.deleted',
  UNAVAILABLE: 'collaboration.unavailable',
} as const;

const lifecycleColor: Record<string, string> = {
  CLOSED: 'default',
  DELETED: 'error',
  UNAVAILABLE: 'warning',
};

function principalName(principal?: ExternalPrincipal | null) {
  if (!principal) return i18n.t('workitem:collaboration.notProvided');
  if (principal.displayName && principal.subjectId) {
    return `${principal.displayName}（${principal.subjectId}）`;
  }
  return principal.displayName || principal.subjectId || i18n.t('workitem:collaboration.notProvided');
}

function PrincipalList({ principals }: { principals: ExternalPrincipal[] }) {
  return (
    <Space size={[8, 8]} wrap>
      {principals.map((principal) => (
        <Space key={principal.id} size={4}>
          <Avatar size={20} icon={<UserOutlined />} />
          <Typography.Text>{principalName(principal)}</Typography.Text>
        </Space>
      ))}
    </Space>
  );
}

export function ExternalCollaborationCard({ collaboration }: ExternalCollaborationCardProps) {
  const { t } = useTranslation('workitem');
  if (!collaboration) {
    return null;
  }

  const sourceLabel = collaboration.provider === 'AONE' ? 'Aone' : collaboration.provider;
  const relationGroups = (collaboration.principalRelations || [])
    .filter((relation) => relation.principals?.length > 0);

  const lifecycleKey = lifecycleLabel[collaboration.sourceLifecycle as keyof typeof lifecycleLabel];
  const syncKey = syncStatusLabel[collaboration.syncStatus as keyof typeof syncStatusLabel];
  const alertType = syncStatusAlertType[collaboration.syncStatus];

  return (
    <Card
      size="small"
      title={t('collaboration.title')}
      style={{ width: '100%' }}
      extra={collaboration.externalUrl ? (
        <Typography.Link href={collaboration.externalUrl} target="_blank" rel="noreferrer">
          <LinkOutlined /> {t('collaboration.open', { provider: sourceLabel })}
        </Typography.Link>
      ) : null}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          columnGap: 24,
          rowGap: 14,
        }}
      >
        <div>
          <Typography.Text type="secondary">{t('collaboration.source')}</Typography.Text>
          <div style={{ marginTop: 4 }}>
            <Space size={8} wrap>
              <Tag>{sourceLabel}</Tag>
              <Typography.Text>{collaboration.externalWorkitemId}</Typography.Text>
              {collaboration.sourceStatusName && <Tag color="orange">{collaboration.sourceStatusName}</Tag>}
              {collaboration.sourceLifecycle !== 'ACTIVE' && (
                <Tag color={lifecycleColor[collaboration.sourceLifecycle] || 'default'}>
                  {lifecycleKey ? t(lifecycleKey) : collaboration.sourceLifecycle}
                </Tag>
              )}
            </Space>
          </div>
        </div>
        {collaboration.reporter && (
          <div>
            <Typography.Text type="secondary">{t('collaboration.reporter')}</Typography.Text>
            <div style={{ marginTop: 4 }}>
              <PrincipalList principals={[collaboration.reporter]} />
            </div>
          </div>
        )}
        {collaboration.businessOwner && (
          <div>
            <Typography.Text type="secondary">{t('collaboration.owner')}</Typography.Text>
            <div style={{ marginTop: 4 }}>
              <PrincipalList principals={[collaboration.businessOwner]} />
            </div>
          </div>
        )}
        {relationGroups.map((relation) => (
          <div key={relation.sourceKey}>
            <Typography.Text type="secondary">{relation.displayName || relation.sourceKey}</Typography.Text>
            <div style={{ marginTop: 4 }}>
              <PrincipalList principals={relation.principals} />
            </div>
          </div>
        ))}
        <div>
          <Typography.Text type="secondary">{t('collaboration.syncStatus')}</Typography.Text>
          <div style={{ marginTop: 4 }}>
            <Tag color={syncStatusColor[collaboration.syncStatus] || 'default'}>
              {syncKey ? t(syncKey) : collaboration.syncStatus}
            </Tag>
            {collaboration.lastSyncAt && (
              <Typography.Text type="secondary">
                {formatDateTime(collaboration.lastSyncAt, SYNC_TIME_FORMAT)}
              </Typography.Text>
            )}
          </div>
        </div>

      </div>
      {alertType && collaboration.lastError && (
        <Alert
          type={alertType}
          showIcon
          message={collaboration.lastErrorCode || t('collaboration.actionRequired')}
          description={collaboration.lastError}
          style={{ marginTop: 16 }}
        />
      )}
    </Card>
  );
}
