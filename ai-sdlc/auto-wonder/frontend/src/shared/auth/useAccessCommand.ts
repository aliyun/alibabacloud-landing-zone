import { useCallback } from 'react';
import { message } from 'antd';
import { ACCESS_LEVEL_LABEL, allows } from './access';
import { useAuthStore } from './store';
import type { OrgAccessLevel } from '@/shared/types/common';

type AccessCommand = <T>(
  required: OrgAccessLevel,
  action: string,
  command: () => T,
) => T | undefined;

export function useAccessCommand(): AccessCommand {
  return useCallback(<T,>(
    required: OrgAccessLevel,
    action: string,
    command: () => T,
  ): T | undefined => {
    const current = useAuthStore.getState().accessLevel;
    if (!allows(current, required)) {
      const currentLabel = current ? ACCESS_LEVEL_LABEL[current] : '未选择组织';
      message.error(`当前为${currentLabel}，${action}需要${ACCESS_LEVEL_LABEL[required]}`);
      return undefined;
    }
    return command();
  }, []);
}
