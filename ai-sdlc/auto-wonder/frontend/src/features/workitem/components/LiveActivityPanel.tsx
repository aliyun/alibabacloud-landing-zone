import { useEffect, useState } from 'react';
import { Typography, Space, Tag, Collapse, Spin, Button } from 'antd';
import { ClockCircleOutlined, LoadingOutlined, ExpandOutlined } from '@ant-design/icons';
import type { LiveActivityAction } from '@/shared/types/workitem';
import { useLiveActivity } from '../useLiveActivity';

import { RuntimeLogDrawer } from './RuntimeLogDrawer';

const RELATIVE_TIME_TICK_MS = 30_000;

const { Text } = Typography;

const ACTION_TYPE_LABELS: Record<string, string> = {
  CONTEXT_PREPARE: '上下文准备',
  REPO_PREPARE: '仓库准备',
  SDLC_STEP: 'SDLC 步骤',
  DISPATCH: '调度',
  HANDOFF: '交接',
  ARTIFACT: '产物',
  COMMAND: '命令执行',
  SKILL_LOAD: 'Skill 加载',
  PLUGIN_LOAD: 'Plugin 加载',
  MCP_LOAD: 'MCP 加载',
  MCP_CALL: 'MCP 调用',
  SKILL: 'Skill 调用',
  SESSION: '会话',
  MODEL_TURN: '推理',
  SUBAGENT: '子代理',
  SEARCH_READ: '检索/读取',
  FILE_EDIT: '文件修改',
  TOOL: '工具调用',
  AGENT: 'Agent',
};

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
  PAUSED: 'warning',
  RESUMED: 'cyan',
  CANCELLED: 'default',
  INFO: 'default',
};

function formatRelativeTime(iso: string | null): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 0) return '刚刚';
  if (diff < 60_000) return `${Math.floor(diff / 1000)}秒前`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
  return `${Math.floor(diff / 3_600_000)}小时前`;
}

function ActionItem({ action }: { action: LiveActivityAction }) {
  const label = ACTION_TYPE_LABELS[action.actionType] ?? action.actionType;
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6, padding: '3px 0' }}>
      <Tag color={STATUS_COLOR[action.status] ?? 'default'} style={{ margin: 0, fontSize: 11, lineHeight: '18px' }}>
        {label}
      </Tag>
      <Text style={{ fontSize: 12, flex: 1, minWidth: 0 }} ellipsis={{ tooltip: action.summary }}>
        {action.summary ?? label}
      </Text>
      {action.eventTime && (
        <Text type="secondary" style={{ fontSize: 11, flexShrink: 0 }}>
          {formatRelativeTime(action.eventTime)}
        </Text>
      )}
    </div>
  );
}

export function LiveActivityPanel({ dispatchId, enabled = true }: { dispatchId: number | null; enabled?: boolean }) {
  const [logOpen, setLogOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [, setTick] = useState(0);
  const { actions, currentAction, lastUpdatedAt, loading, truncated } = useLiveActivity(dispatchId, enabled);

  // Periodic re-render so "最近更新于" / relative timestamps keep advancing while the panel is open.
  useEffect(() => {
    if (!dispatchId) return;
    const id = setInterval(() => setTick((t) => t + 1), RELATIVE_TIME_TICK_MS);
    return () => clearInterval(id);
  }, [dispatchId]);

  if (!dispatchId) return null;

  if (loading && actions.length === 0) {
    return (
      <div style={{ padding: '6px 0' }}>
        <Spin size="small" indicator={<LoadingOutlined />} />
        <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>加载实时动态…</Text>
      </div>
    );
  }

  if (actions.length === 0 && !loading) {
    // A live dispatch with no events yet is "waiting"; a finished/inactive dispatch with no
    // persisted events must not be misreported as still waiting.
    const waiting = enabled;
    return (
      <div style={{ padding: '6px 0' }} data-testid={waiting ? 'live-activity-awaiting' : 'live-activity-empty'}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {waiting ? '正在等待 Agent 上报实时动作' : '本次执行未上报实时动作'}
        </Text>
      </div>
    );
  }

  // actions are seq-ascending (oldest first); collapsed shows the most recent 5, newest first.
  const displayActions = expanded ? actions : actions.slice(-5);

  return (
    <div data-testid="live-activity-panel" style={{ padding: '4px 0' }}>
      {enabled && currentAction && (
        <div style={{ marginBottom: 4, padding: '4px 8px', background: '#e6f4ff', borderRadius: 4 }}>
          <Space size={4}>
            <LoadingOutlined style={{ color: '#1677ff', fontSize: 12 }} />
            <Text strong style={{ fontSize: 12 }} data-testid="live-activity-current">
              {currentAction.summary ?? ACTION_TYPE_LABELS[currentAction.actionType] ?? currentAction.actionType}
            </Text>
          </Space>
        </div>
      )}
      {lastUpdatedAt && (
        <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }} data-testid="live-activity-updated">
          最近更新于 {formatRelativeTime(lastUpdatedAt)}
        </Text>
      )}
      {logOpen && <RuntimeLogDrawer key={dispatchId} dispatchId={dispatchId} onClose={() => setLogOpen(false)} />}
      <Collapse
        ghost
        size="small"
        activeKey={expanded ? ['timeline'] : []}
        onChange={(keys) => setExpanded(Array.isArray(keys) ? keys.includes('timeline') : keys === 'timeline')}
        items={[{
          key: 'timeline',
          extra: <Button size="small" type="text" icon={<ExpandOutlined />} onClick={e => { e.stopPropagation(); setLogOpen(true); }}>放大查看</Button>,
          forceRender: true,
          label: (
            <Text style={{ fontSize: 12 }}>
              近期动作（{actions.length}）{truncated ? ' · 已裁剪' : ''}
            </Text>
          ),
          children: (
            <div data-testid="live-activity-timeline">
              {[...displayActions].reverse().map((action, i) => (
                <ActionItem key={action.eventId ?? `${action.seq}-${i}`} action={action} />
              ))}
            </div>
          ),
        }]}
        style={{ borderRadius: 4, background: '#fafafa' }}
      />
    </div>
  );
}
