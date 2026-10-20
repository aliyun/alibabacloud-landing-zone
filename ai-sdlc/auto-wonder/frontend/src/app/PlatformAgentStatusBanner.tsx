import { useTranslation } from 'react-i18next';
import { useState, type CSSProperties, type ReactNode } from 'react';
import { Alert, Button, Tooltip } from 'antd';
import { ExclamationCircleFilled } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';
import {
  getPlatformAgentStatus,
  platformAgentStatusQueryKey,
} from '@/features/agent/platformAgentStatusApi';

// 常驻横幅按此间隔复查，执行器上线后无需手动刷新即可消失。
export const PLATFORM_AGENT_STATUS_POLL_MS = 30_000;

const DISMISSED_KEY = 'autowonder.platform-agent-status.dismissed';

// 横幅贴在 60px Header 下方,与内容区争高度,故压成单行细条:继承 Header 的水平内边距与 1px 分隔线,
// 去掉 Alert 自带边框/圆角与默认 8px 内边距,字号降到 12px 次级文字级别。
const BANNER_STYLE: CSSProperties = {
  alignItems: 'center',
  border: 'none',
  borderBottom: '1px solid var(--aw-border)',
  borderRadius: 0,
  flexShrink: 0,
  lineHeight: '20px',
  minHeight: 32,
  padding: '5px 24px',
};

const MESSAGE_STYLE: CSSProperties = {
  alignItems: 'center',
  display: 'flex',
  flexWrap: 'wrap',
  fontSize: 12,
  gap: 6,
  minWidth: 0,
};

const TITLE_STYLE: CSSProperties = { fontWeight: 400 };

const HINT_STYLE: CSSProperties = {
  color: 'var(--aw-muted)',
  cursor: 'help',
  fontSize: 12,
};

export function PlatformAgentStatusBanner() {
  const { t } = useTranslation('shell');
  const [dismissed, setDismissed] = useState(() =>
    readViewPreference(DISMISSED_KEY, ['1', '0'], '0') === '1');
  const navigate = useNavigate();
  const isAdmin = useAuthStore((s) => s.hasAccess('ADMIN'));
  const workspaceId = useAuthStore((s) => s.currentWorkspace?.id ?? null);

  const { data } = useQuery({
    queryKey: platformAgentStatusQueryKey(workspaceId),
    queryFn: getPlatformAgentStatus,
    enabled: isAdmin && workspaceId != null && !dismissed,
    refetchInterval: PLATFORM_AGENT_STATUS_POLL_MS,
  });

  if (dismissed || !data || data.state === 'OK') {
    return null;
  }

  const title = data.state === 'NOT_CONFIGURED'
    ? t('banner.notConfigured')
    : t('banner.offline');

  const message: ReactNode = (
    <span style={MESSAGE_STYLE}>
      <ExclamationCircleFilled style={{ color: '#faad14', flexShrink: 0, fontSize: 12 }} />
      <span style={TITLE_STYLE}>{title}</span>
      <Tooltip placement="bottomLeft" title={t('banner.explanation')}>
        <span aria-label={t('banner.capabilities')} style={HINT_STYLE}>{t('banner.details')}</span>
      </Tooltip>
    </span>
  );

  return (
    <Alert
      closable={{ closeIcon: true, 'aria-label': t('banner.dismiss') }}
      onClose={() => {
        setDismissed(true);
        writeViewPreference(DISMISSED_KEY, '1');
      }}
      action={(
        <Button
          size="small"
          type="link"
          onClick={() => navigate('/executors')}
          style={{ fontSize: 12, height: 20, padding: 0 }}
        >{t('banner.configure')}</Button>
      )}
      message={message}
      showIcon={false}
      style={BANNER_STYLE}
      type="warning"
    />
  );
}
