import { useCallback } from 'react';
import { message } from 'antd';
import { ACCESS_LEVEL_LABEL, allows } from './access';
import { useAuthStore } from './store';
import type { WorkspaceAccessLevel } from '@/shared/types/common';

type AccessCommand = <T>(
  required: WorkspaceAccessLevel,
  action: string,
  command: () => T,
) => T | undefined;

export function useAccessCommand(): AccessCommand {
  return useCallback(<T,>(
    required: WorkspaceAccessLevel,
    action: string,
    command: () => T,
  ): T | undefined => {
    const current = useAuthStore.getState().accessLevel;
    if (!allows(current, required)) {
      const currentLabel = current ? ACCESS_LEVEL_LABEL[current] : '未选择工作空间';
      message.error(`当前为${currentLabel}，${action}需要${ACCESS_LEVEL_LABEL[required]}`);
      return undefined;
    }
    return command();
  }, []);
}
