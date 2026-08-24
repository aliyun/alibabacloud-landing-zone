import { Alert, Avatar, Card, Space, Tag, Typography } from 'antd';
import { LinkOutlined, UserOutlined } from '@ant-design/icons';
import type { ExternalCollaboration, ExternalPrincipal } from '@/shared/types/workitem';

interface ExternalCollaborationCardProps {
  collaboration?: ExternalCollaboration | null;
}

const syncStatusLabel: Record<string, string> = {
  HEALTHY: '同步正常',
  DELAYED: '同步延迟',
  ACTION_REQUIRED: '需要处理',
};

const syncStatusColor: Record<string, string> = {
  HEALTHY: 'success',
  DELAYED: 'warning',
  ACTION_REQUIRED: 'error',
};

const lifecycleLabel: Record<string, string> = {
  CLOSED: '已关闭',
  DELETED: '已删除',
  UNAVAILABLE: '暂不可用',
};

const lifecycleColor: Record<string, string> = {
  CLOSED: 'default',
  DELETED: 'error',
  UNAVAILABLE: 'warning',
};

function principalName(principal?: ExternalPrincipal | null) {
  if (!principal) return '未提供';
  if (principal.displayName && principal.subjectId) {
    return `${principal.displayName}（${principal.subjectId}）`;
  }
  return principal.displayName || principal.subjectId || '未提供';
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
  if (!collaboration) {
    return null;
  }

  const sourceLabel = collaboration.provider === 'AONE' ? 'Aone' : collaboration.provider;
  const relationGroups = (collaboration.principalRelations || [])
    .filter((relation) => relation.principals?.length > 0);

  return (
    <Card
      size="small"
      title="外部协作"
      style={{ width: '100%' }}
      extra={collaboration.externalUrl ? (
        <Typography.Link href={collaboration.externalUrl} target="_blank" rel="noreferrer">
          <LinkOutlined /> 打开{sourceLabel}工单
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
          <Typography.Text type="secondary">来源工单</Typography.Text>
          <div style={{ marginTop: 4 }}>
            <Space size={8} wrap>
              <Tag>{sourceLabel}</Tag>
              <Typography.Text>{collaboration.externalWorkitemId}</Typography.Text>
              {collaboration.sourceStatusName && <Tag color="orange">{collaboration.sourceStatusName}</Tag>}
              {collaboration.sourceLifecycle !== 'ACTIVE' && (
                <Tag color={lifecycleColor[collaboration.sourceLifecycle] || 'default'}>
                  {lifecycleLabel[collaboration.sourceLifecycle] || collaboration.sourceLifecycle}
                </Tag>
              )}
            </Space>
          </div>
        </div>
        {collaboration.reporter && (
          <div>
            <Typography.Text type="secondary">需求提出者</Typography.Text>
            <div style={{ marginTop: 4 }}>
              <PrincipalList principals={[collaboration.reporter]} />
            </div>
          </div>
        )}
        {collaboration.businessOwner && (
          <div>
            <Typography.Text type="secondary">外部业务负责人</Typography.Text>
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
          <Typography.Text type="secondary">同步状态</Typography.Text>
          <div style={{ marginTop: 4 }}>
            <Tag color={syncStatusColor[collaboration.syncStatus] || 'default'}>
              {syncStatusLabel[collaboration.syncStatus] || collaboration.syncStatus}
            </Tag>
            {collaboration.lastSyncAt && (
              <Typography.Text type="secondary">
                {new Date(collaboration.lastSyncAt).toLocaleString()}
              </Typography.Text>
            )}
          </div>
        </div>
      </div>
      {collaboration.syncStatus === 'ACTION_REQUIRED' && collaboration.lastError && (
        <Alert
          type="warning"
          showIcon
          message={collaboration.lastErrorCode || '外部工单同步需要处理'}
          description={collaboration.lastError}
          style={{ marginTop: 16 }}
        />
      )}
    </Card>
  );
}
