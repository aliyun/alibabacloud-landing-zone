/**
 * 需求澄清面板的发送方式偏好。
 *
 * 默认 Shift+回车发送（与主流 IM 一致），选择持久化到用户级 `user_setting`，
 * 跨会话、跨设备生效，所以这里不做任何 localStorage 兜底：本地记忆会造成
 * 「这台机器按 A 发送、那台按 B 发送」的分叉。
 */
import { userSettingQueryKey } from '@/features/profile/userSettingApi';

export type SendMode = 'shift-enter' | 'enter';

export const CLARIFICATION_SEND_MODE_KEY = 'clarification_send_mode';

export const DEFAULT_SEND_MODE: SendMode = 'shift-enter';

export const CLARIFICATION_SEND_MODE_QUERY_KEY =
  userSettingQueryKey(CLARIFICATION_SEND_MODE_KEY);

export const SEND_MODE_LABELS: Record<SendMode, string> = {
  'shift-enter': 'Shift+回车发送',
  enter: '回车发送',
};

export const SEND_MODE_OPTIONS = [
  { value: 'shift-enter' as const, label: SEND_MODE_LABELS['shift-enter'] },
  { value: 'enter' as const, label: SEND_MODE_LABELS.enter },
];

/**
 * value_json 是任意 JSON，可能是 null（从未设置）、可能是后续版本写的别的形状。
 * 任何无法识别的值都回落默认，而不是让 undefined 决定按下回车时到底发不发送。
 */
export function parseSendMode(valueJson: string | null | undefined): SendMode {
  if (valueJson == null) return DEFAULT_SEND_MODE;
  let parsed: unknown;
  try {
    parsed = JSON.parse(valueJson);
  } catch {
    return DEFAULT_SEND_MODE;
  }
  return parsed === 'enter' || parsed === 'shift-enter' ? parsed : DEFAULT_SEND_MODE;
}

export function shouldSendOnKey(
  mode: SendMode,
  event: { key: string; shiftKey: boolean },
): boolean {
  if (event.key !== 'Enter') return false;
  return mode === 'enter' ? !event.shiftKey : event.shiftKey;
}
