import type { TFunction } from 'i18next';

/** Only finite server-owned browser turn sources use text notices; AI and replay stay raw. */
export function conversationNotice(entry: unknown, field: 'content' | 'error', t: TFunction<'workitem'>): string | null {
  if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return null;
  const { code, args } = entry as { code?: unknown; args?: unknown };
  if (!args || typeof args !== 'object' || Array.isArray(args)) return null;
  const values = args as Record<string, unknown>;
  if (code === 'QUESTION_EXPIRED' && Object.keys(values).length === 0) return t('clarification.ownedNotice.questionExpired');
  if (field === 'content' && Object.keys(values).length === 0) {
    if (code === 'CANCELED') return t('clarification.ownedNotice.canceled');
    if (code === 'EMPTY_OUTPUT') return t('clarification.ownedNotice.emptyOutput');
  }
  if (Object.keys(values).length !== 1) return null;
  if (typeof values.detail === 'string') {
    const detail = values.detail;
    if (field === 'content') {
      if (code === 'REPLY_FAILED') return t('clarification.ownedNotice.replyFailed', { detail });
      if (code === 'DISPATCH_REPLY_FAILED') return t('clarification.ownedNotice.dispatchReplyFailed', { detail });
    } else {
      if (code === 'DISPATCH_FAILED') return t('clarification.ownedNotice.dispatchFailed', { detail });
      if (code === 'EXECUTOR_COMPATIBILITY_FAILED') return t('clarification.ownedNotice.executorCompatibilityFailed', { detail });
    }
  }
  if (field === 'error' && code === 'DELIVERY_ATTEMPTS_EXHAUSTED'
      && typeof values.attempts === 'number' && Number.isInteger(values.attempts)
      && values.attempts >= 0 && values.attempts <= 2147483647) {
    return t('clarification.ownedNotice.deliveryAttemptsExhausted', { attempts: values.attempts });
  }
  return null;
}
