import { formatDateTime } from '@/shared/i18n/format';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { Button, Descriptions, Drawer, Space, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { Notification, NotificationLink } from '@/shared/types/notification';

const { Paragraph, Title } = Typography;
const WORKITEM_PATH_PREFIX = '/workitems/';

interface Props {
  notification: Notification | null;
  open: boolean;
  onClose: () => void;
}

const typeKeys = {
  WORKITEM_ASSIGNED: 'types.WORKITEM_ASSIGNED',
  COMMENT_MENTION: 'types.COMMENT_MENTION',
  WORKSPACE_ACCESS_REQUEST: 'types.WORKSPACE_ACCESS_REQUEST',
  WORKSPACE_ACCESS_REVIEWED: 'types.WORKSPACE_ACCESS_REVIEWED',
  WATCHED_COMMENT: 'types.WATCHED_COMMENT',
  WATCHED_ASSIGNED: 'types.WATCHED_ASSIGNED',
  WATCHED_STATUS_CHANGED: 'types.WATCHED_STATUS_CHANGED',
  WATCHED_COMPLETED: 'types.WATCHED_COMPLETED',
  EXTERNAL_COMMENT: 'types.EXTERNAL_COMMENT',
  SCHEDULED_TASK_RESUME_DEGRADED: 'types.SCHEDULED_TASK_RESUME_DEGRADED',
  SCHEDULED_TASK_OWNER_INACTIVE: 'types.SCHEDULED_TASK_OWNER_INACTIVE',
  DELIVERY_BLOCKED: 'types.DELIVERY_BLOCKED',
  AI_SESSION_DONE: 'types.AI_SESSION_DONE',
  REVIEW_REQUIRED: 'types.REVIEW_REQUIRED',
  SCHEDULED_TASK_RUN_QUEUED: 'types.SCHEDULED_TASK_RUN_QUEUED',
  SCHEDULED_TASK_RUN_STARTING: 'types.SCHEDULED_TASK_RUN_STARTING',
  SCHEDULED_TASK_RUN_WAITING_EXECUTOR: 'types.SCHEDULED_TASK_RUN_WAITING_EXECUTOR',
  SCHEDULED_TASK_RUN_RUNNING: 'types.SCHEDULED_TASK_RUN_RUNNING',
  SCHEDULED_TASK_RUN_WAITING_HUMAN: 'types.SCHEDULED_TASK_RUN_WAITING_HUMAN',
  SCHEDULED_TASK_RUN_PAUSED: 'types.SCHEDULED_TASK_RUN_PAUSED',
  SCHEDULED_TASK_RUN_SUCCEEDED: 'types.SCHEDULED_TASK_RUN_SUCCEEDED',
  SCHEDULED_TASK_RUN_FAILED: 'types.SCHEDULED_TASK_RUN_FAILED',
  SCHEDULED_TASK_RUN_TIMED_OUT: 'types.SCHEDULED_TASK_RUN_TIMED_OUT',
  SCHEDULED_TASK_RUN_CANCELED: 'types.SCHEDULED_TASK_RUN_CANCELED',
  SCHEDULED_TASK_RUN_SKIPPED: 'types.SCHEDULED_TASK_RUN_SKIPPED',
  PASSWORD_RESET_BY_ADMIN: 'types.PASSWORD_RESET_BY_ADMIN',
  DUPLICATE_INTENT: 'types.DUPLICATE_INTENT',
} as const;

const refKeys = {
  WORKITEM: 'refs.WORKITEM',
  WORKSPACE_ACCESS_REQUEST: 'refs.WORKSPACE_ACCESS_REQUEST',
  SCHEDULED_TASK_RUN: 'refs.SCHEDULED_TASK_RUN',
} as const;

/**
 * Only a complete snapshot with server-issued workitem links is renderable. Anything else must
 * fall back to the plain-text body instead of producing an empty or unsafe anchor.
 */
function splitDuplicateLinks(notification: Notification | null) {
  if (!notification || notification.type !== 'DUPLICATE_INTENT' || !notification.relatedLinks) return null;
  const valid = notification.relatedLinks.filter((item) => (
    (item.kind === 'SUBMITTED' || item.kind === 'DUPLICATE')
    && typeof item.title === 'string' && item.title.trim().length > 0
    && typeof item.url === 'string' && item.url.startsWith(WORKITEM_PATH_PREFIX)
  ));
  const submitted = valid.filter((item) => item.kind === 'SUBMITTED');
  const duplicates = valid.filter((item) => item.kind === 'DUPLICATE');
  return submitted.length > 0 && duplicates.length > 0 ? { submitted, duplicates } : null;
}

/** 详情抽屉里能列出多个工单目标时，列表行必须先打开详情让用户挑选，而不是直接跳单一链接。 */
export function hasDuplicateLinks(notification: Notification | null): boolean {
  return splitDuplicateLinks(notification) !== null;
}

function DuplicateIntentLinks({ submitted, duplicates, t }: {
  submitted: NotificationLink[];
  duplicates: NotificationLink[];
  t: TFunction<'notification'>;
}) {
  const renderLinks = (items: NotificationLink[]) => items.map((item) => (
    <Typography.Link
      key={`${item.kind}:${item.url}`}
      href={item.url}
      target="_blank"
      rel="noopener noreferrer"
    >
      {item.title}
    </Typography.Link>
  ));
  return (
    <Space direction="vertical" size="small">
      <div>
        <Typography.Text strong>{t('submittedWorkitem')}</Typography.Text>
        <Space wrap>{renderLinks(submitted)}</Space>
      </div>
      <div>
        <Typography.Text strong>{t('duplicateWorkitems')}</Typography.Text>
        <Space wrap>{renderLinks(duplicates)}</Space>
      </div>
    </Space>
  );
}

function relatedText(notification: Notification | null, t: TFunction<'notification'>): string {
  if (!notification || (!notification.refType && notification.refId == null)) {
    return '—';
  }
  const code = notification.refType;
  const type = code && Object.prototype.hasOwnProperty.call(refKeys, code) ? t(refKeys[code as keyof typeof refKeys]) : code ?? t('unknownRef');
  return notification.refId != null ? `${type} #${notification.refId}` : type;
}

export function NotificationDetailDrawer({ notification, open, onClose }: Props) {
  const { t } = useTranslation('notification');
  const navigate = useNavigate();
  const link = notification?.link;
  const duplicateLinks = splitDuplicateLinks(notification);

  return (
    <Drawer
      title={t('details')}
      closable={{ 'aria-label': t('close') }}
      width="min(520px, 92vw)"
      open={open}
      onClose={onClose}
      maskClosable
      extra={
        link ? (
          <Button type="primary" onClick={() => navigate(link)}>
            {t('openRelated')}
          </Button>
        ) : undefined
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Title level={5} style={{ marginBottom: 0 }}>
          {notification?.title}
        </Title>
        {duplicateLinks ? (
          <DuplicateIntentLinks submitted={duplicateLinks.submitted} duplicates={duplicateLinks.duplicates} t={t} />
        ) : (
          <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
            {notification?.content || t('noBody')}
          </Paragraph>
        )}
        <Descriptions
          column={1}
          size="small"
          bordered
          items={[
            { key: 'type', label: t('type'), children: notification?.type && Object.prototype.hasOwnProperty.call(typeKeys, notification.type) ? t(typeKeys[notification.type as keyof typeof typeKeys]) : notification?.type ?? '—' },
            {
              key: 'time',
              label: t('time'),
              children: notification?.gmtCreate
                ? formatDateTime(notification.gmtCreate, { dateStyle: 'medium', timeStyle: 'medium' })
                : '—',
            },
            { key: 'ref', label: t('related'), children: relatedText(notification, t) },
          ]}
        />
      </Space>
    </Drawer>
  );
}
