function splitMinutes(seconds: number): { h: number; m: number } {
  const totalMin = Math.round(seconds / 60);
  return { h: Math.floor(totalMin / 60), m: totalMin % 60 };
}

export function formatDurationZh(seconds: number): string {
  if (seconds < 60) return `${seconds}秒`;
  const { h, m } = splitMinutes(seconds);
  if (h === 0) return `${Math.round(seconds / 60)}分钟`;
  return m > 0 ? `${h}小时${m}分` : `${h}小时`;
}

export function formatDurationCompact(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const { h, m } = splitMinutes(seconds);
  if (h === 0) return `${Math.round(seconds / 60)}m`;
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

export function formatMinutesZh(minutes: number): string {
  return formatDurationZh(minutes * 60);
}

export function formatMinutesCompact(minutes: number): string {
  return formatDurationCompact(minutes * 60);
}
