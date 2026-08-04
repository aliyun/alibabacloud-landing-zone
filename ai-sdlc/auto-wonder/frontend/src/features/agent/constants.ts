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

const AGENT_ROLE_NAME_SET = new Set(AGENT_ROLE_PRESETS.flatMap((preset) => [preset.roleName, ...(preset.aliases ?? [])]));

export const AGENT_ROLE_NAME_OPTIONS = [...AGENT_ROLE_NAME_SET].map((roleName) => ({ value: roleName }));

export const AGENT_ROLE_CODE_OPTIONS = AGENT_ROLE_PRESETS.map(({ roleName, roleCode }) => ({
  value: roleCode,
  label: `${roleCode}（${roleName}）`,
}));

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
