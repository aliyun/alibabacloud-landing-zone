import { DatePicker } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';

const { RangePicker } = DatePicker;

/** 与「数据洞察 - 人机交互」页相同的预设：近 7 / 30 / 90 / 180 天。 */
export const RANGE_PRESET_DAYS = [7, 30, 90, 180] as const;

/**
 * 与 insights 页一致的可选范围：不晚于今天，不早于「昨天往前 180 天」；
 * 配合 useInsightDateRange 的 maxDays=181 保证窗口最长 181 个自然日。
 */
export function isDashboardDateDisabled(current: Dayjs): boolean {
  return current > dayjs().endOf('day')
    || current.isBefore(dayjs().subtract(1, 'day').subtract(180, 'day'), 'day');
}

/** 复用 insights 页 RangePicker 的配置：不可清空、预设截止昨天、不提供「全部时间」。 */
export function TimeWindowFilter({ value, onChange, ariaLabel }: {
  value: [Dayjs, Dayjs];
  onChange: (value: [Dayjs, Dayjs]) => void;
  ariaLabel: string;
}) {
  const { t } = useTranslation('insights');
  return (
    <RangePicker
      aria-label={ariaLabel}
      popupClassName="space-dashboard-date-popup"
      allowClear={false}
      value={value}
      presets={RANGE_PRESET_DAYS.map((days) => {
        const end = dayjs().subtract(1, 'day');
        return {
          label: t('participation.lastDays', { count: days }),
          value: [end.subtract(days - 1, 'day'), end] as [Dayjs, Dayjs],
        };
      })}
      onChange={(dates) => {
        if (dates && dates[0] && dates[1]) {
          onChange([dates[0], dates[1]]);
        }
      }}
      disabledDate={(current) => isDashboardDateDisabled(current)}
    />
  );
}
