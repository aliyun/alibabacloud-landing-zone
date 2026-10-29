import { liveActivitySummary } from '../runtimeLabels';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useEffect, useState } from 'react';
import { Typography, Space, Tag, Collapse, Spin, Button } from 'antd';
import { ClockCircleOutlined, LoadingOutlined, ExpandOutlined, CodeOutlined } from '@ant-design/icons';
import type { LiveActivityAction } from '@/shared/types/workitem';
import { useLiveActivity } from '../useLiveActivity';

import { RuntimeLogDrawer } from './RuntimeLogDrawer';

const RELATIVE_TIME_TICK_MS = 30_000;

const { Text } = Typography;

const ACTION_TYPE_LABELS: Record<string, string> = {
  get CONTEXT_PREPARE() { return i18n.t('workitem:live.contextPrepare'); },
  get REPO_PREPARE() { return i18n.t('workitem:live.repoPrepare'); },
  get SDLC_STEP() { return i18n.t('workitem:live.sdlcStep'); },
  get DISPATCH() { return i18n.t('workitem:live.dispatch'); },
  get HANDOFF() { return i18n.t('workitem:live.handoff'); },
  get ARTIFACT() { return i18n.t('workitem:artifacts.title'); },
  get COMMAND() { return i18n.t('workitem:live.command'); },
  get SKILL_LOAD() { return i18n.t('workitem:live.skillLoad'); },
  get PLUGIN_LOAD() { return i18n.t('workitem:live.pluginLoad'); },
  get MCP_LOAD() { return i18n.t('workitem:live.mcpLoad'); },
  get MCP_CALL() { return i18n.t('workitem:live.mcpCall'); },
  get SKILL() { return i18n.t('workitem:live.skillCall'); },
  get SESSION() { return i18n.t('workitem:live.session'); },
  get MODEL_TURN() { return i18n.t('workitem:live.modelTurn'); },
  get SUBAGENT() { return i18n.t('workitem:live.subagent'); },
  get SEARCH_READ() { return i18n.t('workitem:live.searchRead'); },
  get FILE_EDIT() { return i18n.t('workitem:live.fileEdit'); },
  get TOOL() { return i18n.t('workitem:live.tool'); },
  get AGENT() { return i18n.t('workitem:live.agent'); },
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
  if (diff < 0) return i18n.t('workitem:live.justNow');
  if (diff < 60_000) return i18n.t('workitem:live.seconds', { count: Math.floor(diff / 1000) });
  if (diff < 3_600_000) return i18n.t('workitem:live.minutes', { count: Math.floor(diff / 60_000) });
  return i18n.t('workitem:live.hours', { count: Math.floor(diff / 3_600_000) });
}

function ActionItem({ action }: { action: LiveActivityAction }) {
  useTranslation('workitem');
  const label = ACTION_TYPE_LABELS[action.actionType] ?? action.actionType;
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6, padding: '3px 0' }}>
      <Tag color={STATUS_COLOR[action.status] ?? 'default'} style={{ margin: 0, fontSize: 11, lineHeight: '18px' }}>
        {label}
      </Tag>
      <Text style={{ fontSize: 12, flex: 1, minWidth: 0 }} ellipsis={{ tooltip: liveActivitySummary(action) }}>
        {liveActivitySummary(action) ?? label}
      </Text>
      {action.eventTime && (
        <Text type="secondary" style={{ fontSize: 11, flexShrink: 0 }}>
          {formatRelativeTime(action.eventTime)}
        </Text>
      )}
    </div>
  );
}

export function LiveActivityPanel({ dispatchId, enabled = true, summaryOnly = false }: { dispatchId: number | null; enabled?: boolean; summaryOnly?: boolean }) {
  const { t } = useTranslation('workitem');
  const [logOpen, setLogOpen] = useState(false);
  const [terminalOpen, setTerminalOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [, setTick] = useState(0);
  const { actions, currentAction, lastUpdatedAt, loading, truncated, runtimeState } = useLiveActivity(dispatchId, enabled);

  // Periodic re-render so "最近更新于" / relative timestamps keep advancing while the panel is open.
  useEffect(() => {
    if (!dispatchId) return;
    const id = setInterval(() => setTick((t) => t + 1), RELATIVE_TIME_TICK_MS);
    return () => clearInterval(id);
  }, [dispatchId]);

  if (!dispatchId) return null;

  const healthMessage = enabled && runtimeState === 'RECOVERING'
    ? t('live.runtimeRecovering')
    : enabled && runtimeState === 'UNKNOWN' ? t('live.runtimeUnknown') : null;
  const healthNotice = healthMessage ? (
    <Text type="warning" role="status" data-testid="live-activity-health" style={{ display: 'block', fontSize: 12, marginBottom: 4 }}>
      {healthMessage}
    </Text>
  ) : null;

  if (loading && actions.length === 0) {
    return (
      <div style={{ padding: '6px 0' }}>
        <Spin size="small" indicator={<LoadingOutlined aria-hidden="true" />} />
        <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>{t('live.loading')}</Text>
      </div>
    );
  }

  if (actions.length === 0 && !loading) {
    // A live dispatch with no events yet is "waiting"; a finished/inactive dispatch with no
    // persisted events must not be misreported as still waiting.
    const waiting = enabled;
    return (
      <div style={{ padding: '6px 0' }} data-testid={waiting ? 'live-activity-awaiting' : 'live-activity-empty'}>
        {healthNotice}
        {!healthMessage && <Text type="secondary" style={{ fontSize: 12 }}>
          <ClockCircleOutlined aria-hidden="true" style={{ marginRight: 4 }} />
          {waiting ? t('live.awaiting') : t('live.empty')}
        </Text>}
      </div>
    );
  }

  if (summaryOnly) {
    const action = currentAction ?? actions[actions.length - 1];
    const failed = action?.status === 'FAILED';
    const toolAction = action && ['COMMAND', 'MCP_CALL', 'TOOL', 'SEARCH_READ', 'FILE_EDIT', 'SKILL'].includes(action.actionType);
    const headline = toolAction ? [ACTION_TYPE_LABELS[action.actionType], action.summaryTarget].filter(Boolean).join(' · ') : action ? liveActivitySummary(action) : null;
    return <div data-testid="live-activity-panel">
      {healthNotice}
      {action && <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        {enabled && currentAction && <LoadingOutlined aria-hidden="true" style={{ color: 'var(--aw-accent-text)', flexShrink: 0 }} />}
        <Text data-testid="live-activity-current" style={{ display: 'block', flex: 1, minWidth: 0 }} ellipsis={{ tooltip: liveActivitySummary(action) }}>{headline ?? ACTION_TYPE_LABELS[action.actionType] ?? action.actionType}</Text>
        {failed && <Tag color="error">{t('delivery.failed')}</Tag>}
      </div>}
      {lastUpdatedAt && <Text type="secondary" data-testid="live-activity-updated" style={{ display: 'block', fontSize: 12 }}>{t('live.updated', { time: formatRelativeTime(lastUpdatedAt) })}</Text>}
    </div>;
  }

  // actions are seq-ascending (oldest first); collapsed shows the most recent 5, newest first.
  const displayActions = expanded ? actions : actions.slice(-5);

  return (
    <div data-testid="live-activity-panel" style={{ padding: '4px 0' }}>
      {healthNotice}
      {enabled && !healthMessage && currentAction && (
        <div style={{ marginBottom: 4, padding: '4px 8px', background: 'rgba(var(--aw-accent-rgb),.10)', borderRadius: 4 }}>
          <Space size={4}>
            <LoadingOutlined aria-hidden="true" style={{ color: 'var(--aw-accent-text)', fontSize: 12 }} />
            <Text strong style={{ fontSize: 12 }} data-testid="live-activity-current">
              {liveActivitySummary(currentAction) ?? ACTION_TYPE_LABELS[currentAction.actionType] ?? currentAction.actionType}
            </Text>
          </Space>
        </div>
      )}
      {lastUpdatedAt && (
        <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }} data-testid="live-activity-updated">
          {t('live.updated', { time: formatRelativeTime(lastUpdatedAt) })}
        </Text>
      )}
      {logOpen && <RuntimeLogDrawer key={dispatchId} dispatchId={dispatchId} onClose={() => setLogOpen(false)} />}
      {terminalOpen && <RuntimeLogDrawer key={`terminal-${dispatchId}`} dispatchId={dispatchId} terminal onClose={() => setTerminalOpen(false)} />}
      <Collapse
        ghost
        size="small"
        activeKey={expanded ? ['timeline'] : []}
        onChange={(keys) => setExpanded(Array.isArray(keys) ? keys.includes('timeline') : keys === 'timeline')}
        items={[{
          key: 'timeline',
          extra: <Space size={0}><Button size="small" type="text" icon={<ExpandOutlined aria-hidden="true" />} onClick={e => { e.stopPropagation(); setLogOpen(true); }}>{t('live.expand')}</Button><Button size="small" type="text" icon={<CodeOutlined aria-hidden="true" />} onClick={e => { e.stopPropagation(); setTerminalOpen(true); }}>{t('runtimeLog.terminal')}</Button></Space>,
          forceRender: true,
          label: (
            <Text style={{ fontSize: 12 }}>
              {t('live.recentActions', { count: actions.length, trimmed: truncated ? t('live.trimmed') : '' })}
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
        style={{ borderRadius: 4, background: 'var(--aw-raised)' }}
      />
    </div>
  );
}
