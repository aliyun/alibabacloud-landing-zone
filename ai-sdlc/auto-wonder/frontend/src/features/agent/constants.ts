import { i18n } from '@/shared/i18n';
import type { EvolutionMode } from './api';

export interface AgentRolePreset {
  roleName: string;
  roleCode: string;
  aliases?: string[];
}

export const AGENT_ROLE_PRESETS: AgentRolePreset[] = [
  { roleName: '前端开发工程师', roleCode: 'FRONTEND_DEV', aliases: ['前端', '前端开发'] },
  { roleName: '后端开发工程师', roleCode: 'BACKEND_DEV', aliases: ['后端', '后端开发'] },
  { roleName: '全栈开发工程师', roleCode: 'FULLSTACK_DEV', aliases: ['全栈', '全栈开发'] },
  { roleName: '移动端开发工程师', roleCode: 'MOBILE_DEV', aliases: ['移动开发', '移动端开发'] },
  { roleName: '测试工程师', roleCode: 'QA', aliases: ['测试'] },
  { roleName: '产品经理', roleCode: 'PM_PRODUCT', aliases: ['产品'] },
  { roleName: '项目经理', roleCode: 'PM_PROJECT', aliases: ['项目管理', 'PM'] },
  { roleName: '技术负责人', roleCode: 'TECH_LEAD', aliases: ['Tech Lead', 'TL', '技术 Lead'] },
  { roleName: '架构师', roleCode: 'ARCHITECT', aliases: ['系统架构'] },
  { roleName: 'DevOps 工程师', roleCode: 'DEVOPS', aliases: ['DevOps', '运维自动化'] },
  { roleName: '数据分析师', roleCode: 'DATA_ANALYST', aliases: ['数据分析'] },
  { roleName: '技术支持工程师', roleCode: 'SUPPORT_ENGINEER', aliases: ['技术支持'] },
  { roleName: '运维工程师', roleCode: 'OPERATION_ENGINEER', aliases: ['运维'] },
  { roleName: 'Code Reviewer', roleCode: 'CODE_REVIEWER', aliases: ['代码审查员', '代码审核员'] },
];

const normalizeRoleText = (text: string) => text.trim().toLowerCase();

export const getRoleCodeByName = (roleName: string) => {
  const normalized = normalizeRoleText(roleName);

  return AGENT_ROLE_PRESETS.find((preset) => {
    if (normalizeRoleText(preset.roleName) === normalized) {
      return true;
    }

    return (preset.aliases ?? []).some((alias) => normalizeRoleText(alias) === normalized);
  })?.roleCode;
};

export const getRoleNameByCode = (roleCode: string) => {
  const normalized = roleCode.trim().toUpperCase();
  return AGENT_ROLE_PRESETS.find((preset) => preset.roleCode === normalized)?.roleName;
};

const roleLabelKeys = {
  FRONTEND_DEV: 'roles.FRONTEND_DEV',
  BACKEND_DEV: 'roles.BACKEND_DEV',
  FULLSTACK_DEV: 'roles.FULLSTACK_DEV',
  MOBILE_DEV: 'roles.MOBILE_DEV',
  QA: 'roles.QA',
  PM_PRODUCT: 'roles.PM_PRODUCT',
  PM_PROJECT: 'roles.PM_PROJECT',
  TECH_LEAD: 'roles.TECH_LEAD',
  ARCHITECT: 'roles.ARCHITECT',
  DEVOPS: 'roles.DEVOPS',
  DATA_ANALYST: 'roles.DATA_ANALYST',
  SUPPORT_ENGINEER: 'roles.SUPPORT_ENGINEER',
  OPERATION_ENGINEER: 'roles.OPERATION_ENGINEER',
  CODE_REVIEWER: 'roles.CODE_REVIEWER',
} as const;

/** Display only: canonical names and codes are never localized in form values or requests. */
export function getAgentRoleLabel(roleCode?: string | null, roleName?: string | null): string {
  const key = Object.prototype.hasOwnProperty.call(roleLabelKeys, roleCode ?? '')
    ? roleLabelKeys[roleCode as keyof typeof roleLabelKeys] : undefined;
  return key ? i18n.t(key, { ns: 'agent' }) : roleName || roleCode || '';
}

const roleAliasKeys = {
  FRONTEND_DEV: ['roleAliases.frontend', 'roleAliases.frontendDevelopment'],
  BACKEND_DEV: ['roleAliases.backend', 'roleAliases.backendDevelopment'],
  FULLSTACK_DEV: ['roleAliases.fullStack', 'roleAliases.fullStackDevelopment'],
  MOBILE_DEV: ['roleAliases.mobileDevelopment', 'roleAliases.mobileAppDevelopment'],
  QA: ['roleAliases.qa'],
  PM_PRODUCT: ['roleAliases.product'],
  PM_PROJECT: ['roleAliases.projectManagement', 'roleAliases.pm'],
  TECH_LEAD: ['roleAliases.techLead', 'roleAliases.tl', 'roleAliases.technicalLead'],
  ARCHITECT: ['roleAliases.systemArchitecture'],
  DEVOPS: ['roleAliases.devops', 'roleAliases.operationsAutomation'],
  DATA_ANALYST: ['roleAliases.dataAnalysis'],
  SUPPORT_ENGINEER: ['roleAliases.technicalSupport'],
  OPERATION_ENGINEER: ['roleAliases.operations'],
  CODE_REVIEWER: ['roleAliases.codeReviewer', 'roleAliases.codeReview'],
} as const;

export function getAgentRoleNameOptions() {
  const options = new Map<string, { value: string; label: string }>();
  for (const preset of AGENT_ROLE_PRESETS) {
    options.set(preset.roleName, { value: preset.roleName, label: getAgentRoleLabel(preset.roleCode) });
    const aliasKeys = roleAliasKeys[preset.roleCode as keyof typeof roleAliasKeys];
    (preset.aliases ?? []).forEach((value, index) => {
      options.set(value, { value, label: i18n.t(aliasKeys[index], { ns: 'agent' }) });
    });
  }
  return [...options.values()];
}

export function getAgentRoleCodeOptions() {
  return AGENT_ROLE_PRESETS.map(({ roleCode }) => ({
    value: roleCode,
    label: i18n.t('roleCodeOption', { ns: 'agent', code: roleCode, role: getAgentRoleLabel(roleCode) }),
  }));
}

const evolutionLabelKeys = {
  MANUAL: 'evolution.MANUAL',
  ASSISTED: 'evolution.ASSISTED',
  AUTO_PROPOSAL: 'evolution.AUTO_PROPOSAL',
} as const;

export function getEvolutionModeLabel(mode?: string | null): string {
  const key = Object.prototype.hasOwnProperty.call(evolutionLabelKeys, mode ?? '')
    ? evolutionLabelKeys[mode as EvolutionMode] : undefined;
  return i18n.t(key || 'evolution.unset', { ns: 'agent' });
}

export function getEvolutionModeOptions() {
  return (Object.keys(evolutionLabelKeys) as EvolutionMode[]).map(value => ({ value, label: getEvolutionModeLabel(value) }));
}

const statusLabelKeys = new Map([
  ['DRAFT', 'display.status.DRAFT'], ['PENDING_REVIEW', 'display.status.PENDING_REVIEW'],
  ['APPROVED', 'display.status.APPROVED'], ['REJECTED', 'display.status.REJECTED'],
  ['ONLINE', 'display.status.ONLINE'], ['OFFLINE', 'display.status.OFFLINE'],
  ['ROLLED_BACK', 'display.status.ROLLED_BACK'],
] as const);

export function getAgentStatusLabel(status: string): string {
  const key = statusLabelKeys.get(status as Parameters<typeof statusLabelKeys.get>[0]);
  return key ? i18n.t(key, { ns: 'agent' }) : status;
}
