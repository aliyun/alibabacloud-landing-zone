import { i18n } from '@/shared/i18n';
import { describe, it, expect } from 'vitest';
import {
  buildExecutorAgentGroups,
  UNKNOWN_AGENT_GROUP_KEY,
  unknownAgentLabel,
} from './executorAgentGroups';
import type { ExecutorVO } from './api';

function executor(overrides: Partial<ExecutorVO> = {}): ExecutorVO {
  return {
    id: 1,
    agentId: 10,
    agentName: 'Alpha',
    name: 'runner-01',
    status: 'OFFLINE',
    clientKind: 'QODER_CLI',
    lastConnectIp: null,
    lastConnectHostname: null,
    lastHeartbeat: null,
    gmtCreate: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

// 最简解析器：直接用执行器自带的 agentName，取不到就归入未知分组。
function plainLabel(_agentId: number, agentName: string | null): string | null {
  return agentName;
}

describe('buildExecutorAgentGroups', () => {
  it('returns no groups for an empty executor list', () => {
    expect(buildExecutorAgentGroups([], plainLabel)).toEqual([]);
  });

  it('groups executors by their owning agent and keeps input order inside a group', () => {
    const groups = buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: 'Alpha', name: 'a-1' }),
      executor({ id: 2, agentId: 20, agentName: 'Beta', name: 'b-1' }),
      executor({ id: 3, agentId: 10, agentName: 'Alpha', name: 'a-2' }),
    ], plainLabel);

    expect(groups.map((group) => group.label)).toEqual(['Alpha', 'Beta']);
    expect(groups[0].key).toBe('agent-10');
    expect(groups[0].agentId).toBe(10);
    expect(groups[0].executors.map((item) => item.name)).toEqual(['a-1', 'a-2']);
    expect(groups[1].key).toBe('agent-20');
    expect(groups[1].agentId).toBe(20);
    expect(groups[1].executors.map((item) => item.name)).toEqual(['b-1']);
  });

  it('summarises ONLINE / BUSY / OFFLINE per group and ignores unrecognised statuses', () => {
    const [group] = buildExecutorAgentGroups([
      executor({ id: 1, status: 'ONLINE' }),
      executor({ id: 2, status: 'ONLINE' }),
      executor({ id: 3, status: 'BUSY' }),
      executor({ id: 4, status: 'OFFLINE' }),
      executor({ id: 5, status: 'SOME_FUTURE_STATUS' }),
    ], plainLabel);

    expect(group.statusSummary).toEqual({ online: 2, busy: 1, offline: 1 });
    expect(group.executors).toHaveLength(5);
  });

  it('passes both agentId and agentName to the label resolver', () => {
    const seen: [number, string | null][] = [];
    buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: 'Alpha' }),
      executor({ id: 2, agentId: 20, agentName: null }),
    ], (agentId, agentName) => {
      seen.push([agentId, agentName]);
      return agentName;
    });

    expect(seen).toEqual([[10, 'Alpha'], [20, null]]);
  });

  it('falls back to the agents list when the executor carries no agentName', () => {
    const agentNames = new Map<number, string>([[10, 'Alpha'], [20, 'Beta']]);
    const groups = buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: null, name: 'a-1' }),
      executor({ id: 2, agentId: 20, agentName: null, name: 'b-1' }),
    ], (agentId, agentName) => agentName ?? agentNames.get(agentId) ?? null);

    expect(groups.map((group) => group.label)).toEqual(['Alpha', 'Beta']);
  });

  it('keeps the squad suffix produced by the resolver as the group label', () => {
    const groups = buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: '全栈开发' }),
    ], (_agentId, agentName) => `${agentName}（独立开发者小队）`);

    expect(groups[0].label).toBe('全栈开发（独立开发者小队）');
  });

  it('keeps same-named agents in separate groups', () => {
    const groups = buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: '全栈开发', name: 'a-1' }),
      executor({ id: 2, agentId: 20, agentName: '全栈开发', name: 'b-1' }),
    ], plainLabel);

    expect(groups).toHaveLength(2);
    expect(groups.map((group) => group.key)).toEqual(['agent-10', 'agent-20']);
  });

  it('buckets executors with no resolvable agent name into 未知 Agent and sorts it last', () => {
    const groups = buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: null, name: 'orphan-1' }),
      executor({ id: 2, agentId: 99, agentName: 'Beta', name: 'b-1' }),
      executor({ id: 3, agentId: 20, agentName: null, name: 'orphan-2' }),
    ], plainLabel);

    expect(groups.map((group) => group.label)).toEqual(['Beta', '未知 Agent']);
    const unknown = groups[1];
    expect(unknown.key).toBe(UNKNOWN_AGENT_GROUP_KEY);
    expect(unknown.agentId).toBeNull();
    expect(unknown.executors.map((item) => item.name)).toEqual(['orphan-1', 'orphan-2']);
    expect(unknown.statusSummary).toEqual({ online: 0, busy: 0, offline: 2 });
  });

  it('treats a blank or whitespace-only resolved label as unknown', () => {
    const groups = buildExecutorAgentGroups([
      executor({ id: 1, agentId: 10, agentName: '   ' }),
      executor({ id: 2, agentId: 20, agentName: 'Beta' }),
    ], plainLabel);

    expect(groups.map((group) => group.key)).toEqual(['agent-20', UNKNOWN_AGENT_GROUP_KEY]);
    expect(groups[1].label).toBe('未知 Agent');
  });
});

it('refreshes unknown-agent text and preserves group identity for prototype status codes', async () => {
  await i18n.changeLanguage('en-US');
  const [group] = buildExecutorAgentGroups([executor({ agentName: null, status: '__proto__' })], plainLabel);
  expect(unknownAgentLabel()).toBe('Unknown Agent');
  expect(group.label).toBe('Unknown Agent');
  expect(group.key).toBe('unknown-agent');
  expect(group.statusSummary).toEqual({ online: 0, busy: 0, offline: 0 });
  await i18n.changeLanguage('zh-CN');
});
