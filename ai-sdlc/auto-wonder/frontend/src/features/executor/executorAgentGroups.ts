import { i18n } from '@/shared/i18n';
import type { ExecutorVO } from './api';

export const unknownAgentLabel = () => i18n.t('executor:unknownAgent');
export const UNKNOWN_AGENT_GROUP_KEY = 'unknown-agent';

export interface ExecutorGroupStatusSummary {
  online: number;
  busy: number;
  offline: number;
}

export interface ExecutorAgentGroup {
  key: string;
  agentId: number | null;
  label: string;
  executors: ExecutorVO[];
  statusSummary: ExecutorGroupStatusSummary;
}

const STATUS_SUMMARY_FIELD: Record<string, keyof ExecutorGroupStatusSummary> = {
  ONLINE: 'online',
  BUSY: 'busy',
  OFFLINE: 'offline',
};

// 返回 null/空白表示该执行器无法归属到任何具名 Agent，统一落到「未知 Agent」分组。
export function buildExecutorAgentGroups(
  executors: readonly ExecutorVO[],
  resolveLabel: (agentId: number, agentName: string | null) => string | null,
): ExecutorAgentGroup[] {
  const groups = new Map<string, ExecutorAgentGroup>();

  for (const executor of executors) {
    const label = resolveLabel(executor.agentId, executor.agentName)?.trim() ?? '';
    const named = label.length > 0;
    const key = named ? `agent-${executor.agentId}` : UNKNOWN_AGENT_GROUP_KEY;

    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        agentId: named ? executor.agentId : null,
        label: named ? label : unknownAgentLabel(),
        executors: [],
        statusSummary: { online: 0, busy: 0, offline: 0 },
      };
      groups.set(key, group);
    }

    group.executors.push(executor);
    const field = Object.prototype.hasOwnProperty.call(STATUS_SUMMARY_FIELD, executor.status) ? STATUS_SUMMARY_FIELD[executor.status] : undefined;
    if (field) {
      group.statusSummary[field] += 1;
    }
  }

  return [...groups.values()].sort((left, right) => {
    if (left.key === UNKNOWN_AGENT_GROUP_KEY) return 1;
    if (right.key === UNKNOWN_AGENT_GROUP_KEY) return -1;
    return left.label.localeCompare(right.label, 'zh-CN');
  });
}
