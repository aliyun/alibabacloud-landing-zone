import { i18n } from '@/shared/i18n';
import { describe, it, expect } from 'vitest';
import { buildAgentSquadNameMap, formatAgentSquadLabel } from './agentSquadLabel';

describe('buildAgentSquadNameMap', () => {
  it('maps every member agent to its squad name', () => {
    const map = buildAgentSquadNameMap([
      { name: '独立开发者小队', memberAgentIds: [40169] },
      { name: '开发+评审双人组', memberAgentIds: [40170, 40171] },
    ]);

    expect(map.get(40169)).toEqual(['独立开发者小队']);
    expect(map.get(40170)).toEqual(['开发+评审双人组']);
    expect(map.get(40171)).toEqual(['开发+评审双人组']);
  });

  it('collects every squad an agent belongs to, in squad order', () => {
    const map = buildAgentSquadNameMap([
      { name: '小队A', memberAgentIds: [1, 2] },
      { name: '小队B', memberAgentIds: [2] },
      { name: '小队C', memberAgentIds: [2, 3] },
    ]);

    expect(map.get(2)).toEqual(['小队A', '小队B', '小队C']);
    expect(map.get(1)).toEqual(['小队A']);
    expect(map.get(3)).toEqual(['小队C']);
  });

  it('ignores squads whose memberAgentIds is null as returned by the list endpoint', () => {
    const map = buildAgentSquadNameMap([
      { name: '未取到成员的小队', memberAgentIds: null },
      { name: '缺字段的小队' },
      { name: '空小队', memberAgentIds: [] },
      { name: '正常小队', memberAgentIds: [7] },
    ]);

    expect(map.size).toBe(1);
    expect(map.get(7)).toEqual(['正常小队']);
  });

  it('returns an empty map when there is no squad', () => {
    expect(buildAgentSquadNameMap([]).size).toBe(0);
  });

  it('leaves agents that belong to no squad absent from the map', () => {
    const map = buildAgentSquadNameMap([{ name: '独立开发者小队', memberAgentIds: [40169] }]);

    expect(map.has(40999)).toBe(false);
    expect(map.get(40999)).toBeUndefined();
  });
});

describe('formatAgentSquadLabel', () => {
  it('appends a single squad name in full-width parentheses', () => {
    expect(formatAgentSquadLabel('全栈开发', ['独立开发者小队'])).toBe('全栈开发（独立开发者小队）');
  });

  it('joins multiple squad names with a full-width comma', () => {
    expect(formatAgentSquadLabel('全栈开发', ['小队A', '小队B'])).toBe('全栈开发（小队A，小队B）');
    expect(formatAgentSquadLabel('全栈开发', ['小队A', '小队B', '小队C']))
      .toBe('全栈开发（小队A，小队B，小队C）');
  });

  it('falls back to 未编队 when the agent has no squad', () => {
    expect(formatAgentSquadLabel('全栈开发', [])).toBe('全栈开发（未编队）');
    expect(formatAgentSquadLabel('全栈开发', undefined)).toBe('全栈开发（未编队）');
  });
});

describe('agent squad label for the 归属 Agent dropdown', () => {
  it('distinguishes same-named agents across squads and marks unassigned ones', () => {
    const squads = [
      { name: '独立开发者小队', memberAgentIds: [40169] },
      { name: '开发+评审双人组', memberAgentIds: [40170, 40171] },
    ];
    const agents = [
      { id: 40169, name: '全栈开发' },
      { id: 40170, name: '全栈开发' },
      { id: 40171, name: '代码评审' },
      { id: 40172, name: '测试工程师' },
    ];

    const squadNameMap = buildAgentSquadNameMap(squads);
    const labels = agents.map(a => formatAgentSquadLabel(a.name, squadNameMap.get(a.id)));

    expect(labels).toEqual([
      '全栈开发（独立开发者小队）',
      '全栈开发（开发+评审双人组）',
      '代码评审（开发+评审双人组）',
      '测试工程师（未编队）',
    ]);
    expect(new Set(labels).size).toBe(labels.length);
  });
});

it('localizes only the unassigned suffix and punctuation at call time', async () => {
  await i18n.changeLanguage('en-US');
  expect(formatAgentSquadLabel('<b>我的 Agent</b>', [])).toBe('<b>我的 Agent</b> (Unassigned)');
  expect(formatAgentSquadLabel('A', ['小队甲', '小队乙'])).toBe('A (小队甲, 小队乙)');
  await i18n.changeLanguage('zh-CN');
});
