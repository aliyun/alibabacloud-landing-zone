import type { CSSProperties, ReactNode } from 'react';
import { Alert, Button, Tooltip } from 'antd';
import { ExclamationCircleFilled } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import {
  getPlatformAgentStatus,
  platformAgentStatusQueryKey,
} from '@/features/agent/platformAgentStatusApi';

const VALUE_PROPOSITION =
  '平台智能体 Chief of Staff 是项目组的智能管家：自动接单并拆解需求、调度数字人协作交付、沉淀团队记忆，让研发流程进入自动驾驶。配置执行器并保持在线后，工单即可全自动流转。';

// 常驻横幅按此间隔复查，执行器上线后无需手动刷新即可消失。
export const PLATFORM_AGENT_STATUS_POLL_MS = 30_000;

const VALUE_PROPOSITION_TRIGGER = '平台智能体能力说明';

// 横幅贴在 60px Header 下方,与内容区争高度,故压成单行细条:继承 Header 的水平内边距与 1px 分隔线,
// 去掉 Alert 自带边框/圆角与默认 8px 内边距,字号降到 12px 次级文字级别。
const BANNER_STYLE: CSSProperties = {
  alignItems: 'center',
  border: 'none',
  borderBottom: '1px solid rgba(0, 0, 0, 0.04)',
  borderRadius: 0,
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
  color: 'rgba(0, 0, 0, 0.45)',
  cursor: 'help',
  fontSize: 12,
};

export function PlatformAgentStatusBanner() {
  const navigate = useNavigate();
  const isAdmin = useAuthStore((s) => s.hasAccess('ADMIN'));
  const workspaceId = useAuthStore((s) => s.currentWorkspace?.id ?? null);

  const { data } = useQuery({
    queryKey: platformAgentStatusQueryKey(workspaceId),
    queryFn: getPlatformAgentStatus,
    enabled: isAdmin && workspaceId != null,
    refetchInterval: PLATFORM_AGENT_STATUS_POLL_MS,
  });

  if (!data || data.state === 'OK') {
    return null;
  }

  const title = data.state === 'NOT_CONFIGURED'
    ? '平台智能体 Chief of Staff 未配置执行器'
    : '平台智能体 Chief of Staff 执行器全部离线';

  const message: ReactNode = (
    <span style={MESSAGE_STYLE}>
      <ExclamationCircleFilled style={{ color: '#faad14', flexShrink: 0, fontSize: 12 }} />
      <span style={TITLE_STYLE}>{title}</span>
      <Tooltip placement="bottomLeft" title={VALUE_PROPOSITION}>
        <span aria-label={VALUE_PROPOSITION_TRIGGER} style={HINT_STYLE}>
          说明
        </span>
      </Tooltip>
    </span>
  );

  return (
    <Alert
      action={(
        <Button
          size="small"
          type="link"
          onClick={() => navigate('/executors')}
          style={{ fontSize: 12, height: 20, padding: 0 }}
        >
          去配置执行器
        </Button>
      )}
      message={message}
      showIcon={false}
      style={BANNER_STYLE}
      type="warning"
    />
  );
}
