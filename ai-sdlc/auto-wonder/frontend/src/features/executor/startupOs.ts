import type { StartupOs } from './api';

/**
 * Only the output format is detected locally. Every launch value comes from the persisted config the server
 * returns, so the page and autowonder.build_executor_launch_command produce byte-identical commands.
 */
export function detectStartupOs(): StartupOs {
  try {
    const platform = (navigator.platform ?? '').toLowerCase();
    const hint = platform || (navigator.userAgent ?? '').toLowerCase();
    // 只认 windows/win32/win64：macOS 的 UA 里带 "darwin"，按 'win' 子串匹配会把 mac 误判成 windows
    if (/win(dows|32|64)/.test(hint)) {
      return 'windows';
    }
  } catch {
    // navigator unavailable — fall through to the posix output format
  }
  return 'posix';
}
