import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCommon from './locales/zh-CN/common.json';
import enCommon from './locales/en-US/common.json';

import zhShell from './locales/zh-CN/shell.json';
import enShell from './locales/en-US/shell.json';
import zhAuth from './locales/zh-CN/auth.json';
import enAuth from './locales/en-US/auth.json';
import zhShared from './locales/zh-CN/shared.json';
import enShared from './locales/en-US/shared.json';
import zhWorkitem from './locales/zh-CN/workitem.json';
import enWorkitem from './locales/en-US/workitem.json';

import zhAgent from './locales/zh-CN/agent.json';
import enAgent from './locales/en-US/agent.json';

import zhSquad from './locales/zh-CN/squad.json';
import enSquad from './locales/en-US/squad.json';

import zhExecutor from './locales/zh-CN/executor.json';
import enExecutor from './locales/en-US/executor.json';

import zhInsights from './locales/zh-CN/insights.json';
import enInsights from './locales/en-US/insights.json';

import zhExecution from './locales/zh-CN/execution.json';
import enExecution from './locales/en-US/execution.json';

import zhEvolution from './locales/zh-CN/evolution.json';
import enEvolution from './locales/en-US/evolution.json';

import zhScheduledTask from './locales/zh-CN/scheduledTask.json';
import enScheduledTask from './locales/en-US/scheduledTask.json';

import zhSdlc from './locales/zh-CN/sdlc.json';
import enSdlc from './locales/en-US/sdlc.json';

import zhStatemachine from './locales/zh-CN/statemachine.json';
import enStatemachine from './locales/en-US/statemachine.json';

import zhRepo from './locales/zh-CN/repo.json';
import enRepo from './locales/en-US/repo.json';

import zhSkill from './locales/zh-CN/skill.json';
import enSkill from './locales/en-US/skill.json';

import zhMemory from './locales/zh-CN/memory.json';
import enMemory from './locales/en-US/memory.json';

import zhIntegration from './locales/zh-CN/integration.json';
import enIntegration from './locales/en-US/integration.json';

import zhSettings from './locales/zh-CN/settings.json';
import enSettings from './locales/en-US/settings.json';

import zhEnvironmentVariables from './locales/zh-CN/environmentVariables.json';
import enEnvironmentVariables from './locales/en-US/environmentVariables.json';
import zhAudit from './locales/zh-CN/audit.json';
import enAudit from './locales/en-US/audit.json';

import zhProfile from './locales/zh-CN/profile.json';
import enProfile from './locales/en-US/profile.json';
import zhMcp from './locales/zh-CN/mcp.json';
import enMcp from './locales/en-US/mcp.json';

import zhPlatform from './locales/zh-CN/platform.json';
import enPlatform from './locales/en-US/platform.json';
import zhNotification from './locales/zh-CN/notification.json';
import enNotification from './locales/en-US/notification.json';
import zhAbout from './locales/zh-CN/about.json';
import enAbout from './locales/en-US/about.json';

import zhHelp from './locales/zh-CN/help.json';
import enHelp from './locales/en-US/help.json';

import zhOverview from './locales/zh-CN/overview.json';
import enOverview from './locales/en-US/overview.json';

import zhChiefOfStaff from './locales/zh-CN/chiefOfStaff.json';
import enChiefOfStaff from './locales/en-US/chiefOfStaff.json';
import zhUserManagement from './locales/zh-CN/userManagement.json';
import enUserManagement from './locales/en-US/userManagement.json';
import zhSpaceDashboard from './locales/zh-CN/spaceDashboard.json';
import enSpaceDashboard from './locales/en-US/spaceDashboard.json';

export type Locale = 'zh-CN' | 'en-US';
export function normalizeLocale(value: unknown): Locale {
  return typeof value === 'string' && /^en(?:[-_]|$)/i.test(value) ? 'en-US' : 'zh-CN';
}
export function browserLocale(): Locale {
  const languages = typeof navigator === 'undefined' ? [] : navigator.languages || [navigator.language];
  return normalizeLocale(languages.find(value => /^(en|zh)(?:[-_]|$)/i.test(value)));
}
export const resources = {
  'zh-CN': { chiefOfStaff: zhChiefOfStaff, common: zhCommon, shell: zhShell, auth: zhAuth, shared: zhShared, workitem: zhWorkitem, agent: zhAgent, squad: zhSquad, executor: zhExecutor, insights: zhInsights, execution: zhExecution, evolution: zhEvolution, scheduledTask: zhScheduledTask, sdlc: zhSdlc, statemachine: zhStatemachine, repo: zhRepo, skill: zhSkill, memory: zhMemory, integration: zhIntegration, settings: zhSettings, environmentVariables: zhEnvironmentVariables, audit: zhAudit, profile: zhProfile, mcp: zhMcp, platform: zhPlatform, notification: zhNotification, about: zhAbout, help: zhHelp, overview: zhOverview, userManagement: zhUserManagement, spaceDashboard: zhSpaceDashboard },
  'en-US': { chiefOfStaff: enChiefOfStaff, common: enCommon, shell: enShell, auth: enAuth, shared: enShared, workitem: enWorkitem, agent: enAgent, squad: enSquad, executor: enExecutor, insights: enInsights, execution: enExecution, evolution: enEvolution, scheduledTask: enScheduledTask, sdlc: enSdlc, statemachine: enStatemachine, repo: enRepo, skill: enSkill, memory: enMemory, integration: enIntegration, settings: enSettings, environmentVariables: enEnvironmentVariables, audit: enAudit, profile: enProfile, mcp: enMcp, platform: enPlatform, notification: enNotification, about: enAbout, help: enHelp, overview: enOverview, userManagement: enUserManagement, spaceDashboard: enSpaceDashboard },
};
export const i18n = i18next.createInstance();
void i18n.use(initReactI18next).init({
  resources, lng: 'zh-CN', fallbackLng: 'zh-CN', supportedLngs: ['zh-CN', 'en-US'],
  defaultNS: 'common', initAsync: false, interpolation: { escapeValue: false },
});

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: typeof resources['zh-CN'];
    strictKeyChecks: true;
  }
}
