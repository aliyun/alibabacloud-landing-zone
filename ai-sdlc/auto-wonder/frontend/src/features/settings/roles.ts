import { i18n } from '@/shared/i18n';
import { isWorkspaceAccessLevel } from '@/shared/auth/access';

export function workspaceRoleLabel(role: { id: number; code: string | null; name: string | null }): string {
  if (isWorkspaceAccessLevel(role.code)) return i18n.t(`settings:builtInRole.${role.code}`);
  return role.name ?? i18n.t('settings:migratedRole', { id: role.id });
}
