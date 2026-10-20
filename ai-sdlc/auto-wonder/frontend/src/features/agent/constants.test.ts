import { afterEach, it, expect } from 'vitest';
import { i18n } from '@/shared/i18n';
import { getAgentStatusLabel, getAgentRoleLabel, getEvolutionModeLabel, getAgentRoleNameOptions, getAgentRoleCodeOptions, getEvolutionModeOptions } from './constants';

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it('localizes known codes while preserving custom content for unknown and object-prototype role codes', async () => {
  await i18n.changeLanguage('en-US');
  expect(getAgentRoleLabel('QA', '测试工程师')).toBe('QA engineer');
  for (const code of ['CUSTOM', 'constructor', '__proto__', 'toString']) {
    expect(getAgentRoleLabel(code, '<strong>用户角色</strong>')).toBe('<strong>用户角色</strong>');
    expect(getEvolutionModeLabel(code)).toBe('Not set');
  }
  expect(getAgentRoleLabel(null, '自定义角色')).toBe('自定义角色');
  expect(getAgentRoleLabel('CUSTOM')).toBe('CUSTOM');
  expect(getAgentRoleLabel()).toBe('');
});

it('returns fresh bilingual labels with canonical option values and evolution codes unchanged', async () => {
  await i18n.changeLanguage('en-US');
  expect(getAgentRoleNameOptions()).toContainEqual({ value: '前端开发', label: 'Frontend development' });
  expect(getAgentRoleCodeOptions()).toContainEqual({ value: 'QA', label: 'QA (QA engineer)' });
  expect(getEvolutionModeOptions()).toEqual([
    { value: 'MANUAL', label: 'Manual' },
    { value: 'ASSISTED', label: 'Assisted review (recommended)' },
    { value: 'AUTO_PROPOSAL', label: 'Automatic proposals' },
  ]);
  await i18n.changeLanguage('zh-CN');
  expect(getAgentRoleNameOptions()).toContainEqual({ value: '前端开发', label: '前端开发' });
  expect(getAgentRoleCodeOptions()).toContainEqual({ value: 'QA', label: 'QA（测试工程师）' });
});

it('localizes finite agent/version statuses and preserves unknown codes including prototype names', async () => {
  await i18n.changeLanguage('en-US');
  expect(['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'ONLINE', 'OFFLINE', 'ROLLED_BACK'].map(getAgentStatusLabel))
    .toEqual(['Draft', 'Pending review', 'Approved', 'Rejected', 'Online', 'Offline', 'Rolled back']);
  for (const code of ['constructor', '__proto__', 'CUSTOM']) expect(getAgentStatusLabel(code)).toBe(code);
  await i18n.changeLanguage('zh-CN');
  expect(getAgentStatusLabel('PENDING_REVIEW')).toBe('待审核');
});
