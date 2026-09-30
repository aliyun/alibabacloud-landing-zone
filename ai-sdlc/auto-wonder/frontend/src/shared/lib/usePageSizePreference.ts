import { useCallback, useState } from 'react';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';

/**
 * 浏览器本地记住每页显示条数：刷新/重进页面后保持上次选择。
 * 取值白名单校验（不在 pageSizeOptions 里的脏值回落默认），localStorage
 * 不可用时静默降级为仅内存态。
 */
export function usePageSizePreference(
  key: string,
  allowed: readonly number[],
  fallback: number,
) {
  const allowedKeys = allowed.map(String);
  const [pageSize, setPageSize] = useState(() =>
    Number(readViewPreference(key, allowedKeys, String(fallback))));

  const changePageSize = useCallback((next: number) => {
    setPageSize(next);
    writeViewPreference(key, String(next));
  }, [key]);

  return [pageSize, changePageSize] as const;
}
