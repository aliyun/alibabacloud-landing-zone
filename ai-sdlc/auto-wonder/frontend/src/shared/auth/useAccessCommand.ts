import { i18n } from '@/shared/i18n';
import { useCallback } from 'react';
import { message } from 'antd';
import { ACCESS_LEVEL_LABEL, allows, workspaceDomainLabel } from './access';
import { useAuthStore } from './store';
import type { WorkspaceAccessLevel, WorkspaceDomain } from '@/shared/types/common';

type AccessCommand = <T>(
  required: WorkspaceAccessLevel,
  action: string,
  command: () => T,
  domain?: WorkspaceDomain,
) => T | undefined;

export function useAccessCommand(): AccessCommand {
  return useCallback(<T,>(
    required: WorkspaceAccessLevel,
    action: string,
    command: () => T,
    domain?: WorkspaceDomain,
  ): T | undefined => {
    const state = useAuthStore.getState();
    if (required !== 'READ_ONLY' && state.accessLevel !== 'ADMIN'
      && domain && state.readOnlyDomains.includes(domain)) {
      message.error(i18n.t('shared:access.domainReadOnly', {
        action,
        domain: workspaceDomainLabel(domain),
      }));
      return undefined;
    }
    if (!allows(state.accessLevel, required)) {
      const currentLabel = state.accessLevel
        ? ACCESS_LEVEL_LABEL[state.accessLevel]
        : i18n.t('shared:access.noWorkspace');
      message.error(i18n.t('shared:access.denied', { current: currentLabel, action, required: ACCESS_LEVEL_LABEL[required] }));
      return undefined;
    }
    return command();
  }, []);
}
