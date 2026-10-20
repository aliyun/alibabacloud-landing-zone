import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';

/** 高级筛选的「创建时间」条件草稿：两侧均可为空，支持只设置起始或只设置结束日期。 */
export interface CreatedTimeRangeDraft {
  from: Dayjs | null;
  to: Dayjs | null;
}

export const EMPTY_CREATED_TIME_RANGE: CreatedTimeRangeDraft = { from: null, to: null };

export function hasCreatedTimeRange(range: CreatedTimeRangeDraft): boolean {
  return !!range.from || !!range.to;
}

/** 起始日晚于结束日时为无效区间：页面据此提示且不提交查询。 */
export function isCreatedTimeRangeInvalid(range: CreatedTimeRangeDraft): boolean {
  if (!range.from || !range.to) {
    return false;
  }
  return range.from.startOf('day').isAfter(range.to.startOf('day'));
}

/**
 * 把页面所选日期换算成服务端边界：日期按页面（浏览器）时区解释，
 * 起始日取当天起点作为含下界，结束日取次日 00:00 作为排他上界，保证结束日全天纳入。
 */
export function toCreatedTimeQuery(range: CreatedTimeRangeDraft): { createdFrom?: string; createdTo?: string } {
  if (isCreatedTimeRangeInvalid(range)) {
    return {};
  }
  const query: { createdFrom?: string; createdTo?: string } = {};
  if (range.from) {
    query.createdFrom = range.from.startOf('day').toDate().toISOString();
  }
  if (range.to) {
    query.createdTo = range.to.startOf('day').add(1, 'day').toDate().toISOString();
  }
  return query;
}

/** 高级筛选的快捷范围：近一周＝含今天在内最近 7 天；近一个月＝含今天在内最近 30 天。 */
export type CreatedTimePresetKey = 'lastWeek' | 'lastMonth';

const PRESET_DAYS: Record<CreatedTimePresetKey, number> = { lastWeek: 7, lastMonth: 30 };

/** 按页面时区相对「今天」生成快捷区间，两端归一到当天起点，与手选日期共用同一套边界语义。 */
export function buildCreatedTimeRangePreset(
  preset: CreatedTimePresetKey,
  today: Dayjs = dayjs(),
): CreatedTimeRangeDraft {
  const end = today.startOf('day');
  return { from: end.subtract(PRESET_DAYS[preset] - 1, 'day'), to: end };
}
