import { i18n } from '@/shared/i18n';
import type { WorkspaceAccessLevel, WorkspaceDomain } from '@/shared/types/common';

const ACCESS_LEVEL_RANK: Record<WorkspaceAccessLevel, number> = {
  READ_ONLY: 0,
  READ_WRITE: 1,
  ADMIN: 2,
};

export const ACCESS_LEVEL_LABEL: Record<WorkspaceAccessLevel, string> = {
  get READ_ONLY() { return i18n.t('shared:access.readOnly'); },
  get READ_WRITE() { return i18n.t('shared:access.readWrite'); },
  get ADMIN() { return i18n.t('shared:access.admin'); },
};

export const WORKSPACE_DOMAINS: WorkspaceDomain[] = [
  'AGENT', 'AI', 'AIUSAGE', 'ARTIFACT', 'AUDIT', 'AUTH', 'BACKUP',
  'CATEGORY', 'CLARIFICATION', 'CONVERSATION', 'DASHBOARD', 'DEBUGLOG',
  'DISPATCH', 'ENVIRONMENT', 'EVOLUTION', 'EXECUTOR', 'INSIGHTS',
  'INTEGRATION', 'MCP', 'MEMORY', 'REPO', 'SCHEDULEDTASK', 'SDLC',
  'SETTING', 'SKILL', 'SQUAD', 'STATEMACHINE', 'TEMPLATE', 'USER',
  'WORKITEM', 'WORKSPACE',
];

const DOMAIN_SET = new Set<string>(WORKSPACE_DOMAINS);

export function isWorkspaceAccessLevel(value: unknown): value is WorkspaceAccessLevel {
  return value === 'READ_ONLY' || value === 'READ_WRITE' || value === 'ADMIN';
}

export function isWorkspaceDomain(value: unknown): value is WorkspaceDomain {
  return typeof value === 'string' && DOMAIN_SET.has(value);
}

export function workspaceDomainLabel(domain: WorkspaceDomain): string {
  return i18n.t(`shared:access.domain.${domain}`);
}

export function allows(current: WorkspaceAccessLevel | null, required: WorkspaceAccessLevel): boolean {
  return current !== null && ACCESS_LEVEL_RANK[current] >= ACCESS_LEVEL_RANK[required];
}
