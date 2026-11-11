import { useCallback, useEffect, useRef, useState } from 'react';
import {
  checkWorkitemDuplicates,
  getWorkitemDuplicateCheck,
  type DuplicateCheckResult,
  type DuplicateMatch,
} from './api';

/** 停止输入后触发预检的等待时间。 */
export const DUPLICATE_CHECK_IDLE_MS = 600;
/** 意图结果未就绪时的一次续查间隔与次数上限；不做无界轮询。 */
export const DUPLICATE_INTENT_POLL_MS = 3000;
export const DUPLICATE_INTENT_MAX_POLLS = 3;

export interface DuplicateCheckModalState {
  open: boolean;
  matches: DuplicateMatch[];
  /** 同名候选超出服务端扫描上限。 */
  truncated: boolean;
  /** 意图检测未覆盖全部候选。 */
  partial: boolean;
}

export interface UseWorkitemDuplicateCheckOptions {
  title: string;
  excludeWorkitemId?: number | string;
  enabled?: boolean;
}

const EMPTY_STATE: DuplicateCheckModalState = { open: false, matches: [], truncated: false, partial: false };

function mergeMatches(result: DuplicateCheckResult): DuplicateMatch[] {
  const seen = new Set<number>();
  const merged: DuplicateMatch[] = [];
  for (const match of [...(result.exactMatches ?? []), ...(result.intentMatches ?? [])]) {
    if (match && !seen.has(match.id)) {
      seen.add(match.id);
      merged.push(match);
    }
  }
  return merged;
}

/**
 * 新建/编辑共用：标题输入完成后自动预检重复工单。
 * 查重失败一律静默；提交后迟到的结果不再弹窗；相同标题与候选只提醒一次。
 */
export function useWorkitemDuplicateCheck({
  title,
  excludeWorkitemId,
  enabled = true,
}: UseWorkitemDuplicateCheckOptions) {
  const [state, setState] = useState<DuplicateCheckModalState>(EMPTY_STATE);
  const sessionId = useRef<string>(
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `form-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  );
  const revision = useRef(0);
  const lastShownKey = useRef('');
  /** 已确认无命中的「标题+排除 ID」；同标题反复失焦不再重复请求。 */
  const lastNoMatchKey = useRef('');
  const composing = useRef(false);
  const submitted = useRef(false);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimers = useCallback(() => {
    if (idleTimer.current) {
      clearTimeout(idleTimer.current);
      idleTimer.current = null;
    }
    if (pollTimer.current) {
      clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const applyResult = useCallback((result: DuplicateCheckResult, baseKey: string) => {
    const matches = mergeMatches(result);
    if (matches.length === 0) {
      lastNoMatchKey.current = baseKey;
      return;
    }
    lastNoMatchKey.current = '';
    // 去重键包含命中的候选集合：候选变化后允许再次提醒。
    const key = `${baseKey}|${matches.map((match) => match.id).sort((left, right) => left - right).join(',')}`;
    if (lastShownKey.current === key) {
      return;
    }
    lastShownKey.current = key;
    setState({ open: true, matches, truncated: Boolean(result.exactTruncated), partial: Boolean(result.intentPartial) });
  }, []);

  const runCheck = useCallback(
    async (value: string) => {
      const trimmed = value.trim();
      if (!enabled || submitted.current || composing.current || !trimmed) {
        return;
      }
      const key = `${trimmed}|${excludeWorkitemId ?? ''}`;
      if (lastNoMatchKey.current === key) {
        return;
      }
      const requestRevision = ++revision.current;
      try {
        const result = await checkWorkitemDuplicates({
          title: trimmed,
          excludeWorkitemId,
          formRequestId: sessionId.current,
        });
        if (requestRevision !== revision.current || submitted.current) {
          return;
        }
        applyResult(result, key);
        const pending = result.intentEnabled && result.checkId
          && (result.intentStatus === 'PENDING' || result.intentStatus === 'RUNNING');
        if (pending) {
          scheduleIntentPoll(result.checkId as number, key, requestRevision, 1);
        }
      } catch {
        // 预检失败保持静默：不提示、不阻断创建或保存。
      }
    },
    // scheduleIntentPoll 只依赖 refs 与自身，不参与依赖数组
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [applyResult, enabled, excludeWorkitemId],
  );

  const scheduleIntentPoll = useCallback(
    (checkId: number, key: string, requestRevision: number, attempt: number) => {
      if (attempt > DUPLICATE_INTENT_MAX_POLLS) {
        return;
      }
      pollTimer.current = setTimeout(async () => {
        if (submitted.current || requestRevision !== revision.current) {
          return;
        }
        try {
          const result = await getWorkitemDuplicateCheck(checkId);
          if (submitted.current || requestRevision !== revision.current) {
            return;
          }
          if (result.intentStatus === 'PENDING' || result.intentStatus === 'RUNNING') {
            scheduleIntentPoll(checkId, key, requestRevision, attempt + 1);
            return;
          }
          applyResult(result, key);
        } catch {
          // 查询失败静默结束，不再重试。
        }
      }, DUPLICATE_INTENT_POLL_MS);
    },
    [applyResult],
  );

  useEffect(() => {
    if (idleTimer.current) {
      clearTimeout(idleTimer.current);
      idleTimer.current = null;
    }
    if (!enabled || submitted.current || composing.current || !title.trim()) {
      return undefined;
    }
    idleTimer.current = setTimeout(() => {
      idleTimer.current = null;
      void runCheck(title);
    }, DUPLICATE_CHECK_IDLE_MS);
    return () => {
      if (idleTimer.current) {
        clearTimeout(idleTimer.current);
        idleTimer.current = null;
      }
    };
  }, [enabled, runCheck, title]);

  useEffect(() => () => clearTimers(), [clearTimers]);

  const onBlur = useCallback(() => {
    if (!enabled || submitted.current || composing.current) {
      return;
    }
    if (idleTimer.current) {
      clearTimeout(idleTimer.current);
      idleTimer.current = null;
    }
    void runCheck(title);
  }, [enabled, runCheck, title]);

  const onCompositionStart = useCallback(() => {
    composing.current = true;
  }, []);

  const onCompositionEnd = useCallback(() => {
    composing.current = false;
  }, []);

  const dismiss = useCallback(() => {
    setState((previous) => ({ ...previous, open: false }));
  }, []);

  /** 提交/保存后调用：关闭弹窗并让迟到结果失效。 */
  const markSubmitted = useCallback(() => {
    submitted.current = true;
    revision.current += 1;
    clearTimers();
    setState((previous) => ({ ...previous, open: false }));
  }, [clearTimers]);

  /** 重新进入编辑态时允许再次提醒。 */
  const resetSubmission = useCallback(() => {
    submitted.current = false;
  }, []);

  return {
    modal: state,
    dismiss,
    markSubmitted,
    resetSubmission,
    titleHandlers: {
      onBlur,
      onCompositionStart,
      onCompositionEnd,
    },
  };
}
