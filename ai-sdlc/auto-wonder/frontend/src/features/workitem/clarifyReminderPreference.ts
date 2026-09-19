/**
 * 「启动交付」前的需求澄清引导降噪开关（工单 54819）。
 *
 * 作用域是用户级而不是按工单：勾选「下次不再提醒」后所有工单都不再弹引导。
 * 存 localStorage 而非后端：这是按浏览器生效的降噪偏好，与仓库既有 UI 偏好
 * （侧栏折叠、列表范围、澄清 squad/agent 预填）同一套做法。
 */
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';

const CLARIFY_REMINDER_STORAGE_KEY = 'autowonder.workitems.clarifyReminder';
const CLARIFY_REMINDER_OPTIONS = ['remind', 'suppressed'] as const;
const REMIND = CLARIFY_REMINDER_OPTIONS[0];
const SUPPRESSED = CLARIFY_REMINDER_OPTIONS[1];

export function isClarifyReminderSuppressed(): boolean {
  return readViewPreference(
    CLARIFY_REMINDER_STORAGE_KEY,
    CLARIFY_REMINDER_OPTIONS,
    REMIND,
  ) === SUPPRESSED;
}

export function suppressClarifyReminder(): void {
  writeViewPreference(CLARIFY_REMINDER_STORAGE_KEY, SUPPRESSED);
}
