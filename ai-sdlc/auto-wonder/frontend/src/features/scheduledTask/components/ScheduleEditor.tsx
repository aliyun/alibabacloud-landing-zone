import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { Alert, DatePicker, Form, Input, Segmented, Space, TimePicker, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import type { ScheduleType } from '../types';
import { previewScheduledTask } from '../api';

const previewDateOptions: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' };

export type SchedulePreset = 'hourly' | 'daily' | 'weekly' | 'monthly' | 'custom';
export const PRESET_CRON: Record<Exclude<SchedulePreset, 'custom'>, string> = {
  hourly: '0 0 * * * *', daily: '0 0 2 * * *', weekly: '0 0 2 * * 2', monthly: '0 0 2 1 * *',
};

export function isSixFieldCron(value?: string): boolean {
  if (!value || value.trim().split(/\s+/).length !== 6) return false;
  return value.trim().split(/\s+/).every((part) => /^[0-9*?,/\-LW#]+$/.test(part));
}

export function ScheduleEditor({ timezone = 'Asia/Shanghai' }: { timezone?: string }) {
  const { t } = useTranslation('scheduledTask');
  const form = Form.useFormInstance();
  const scheduleType = (Form.useWatch('scheduleType', form) as ScheduleType | undefined) ?? 'CRON';
  const cronExpression = (Form.useWatch('cronExpression', form) as string | undefined) ?? '0 0 2 * * *';
  const preset: SchedulePreset = (Form.useWatch('schedulePreset', form) as SchedulePreset | undefined) ?? 'daily';
  const preview = useQuery({
    queryKey: ['scheduled-tasks', 'preview', cronExpression, timezone],
    queryFn: () => previewScheduledTask(cronExpression!, timezone),
    enabled: scheduleType === 'CRON' && isSixFieldCron(cronExpression),
  });
  const changePreset = (next: SchedulePreset) => {
    form.setFieldValue('schedulePreset', next);
    if (next !== 'custom') form.setFieldValue('cronExpression', PRESET_CRON[next]);
  };

  return <>
    <Form.Item name="scheduleType" label={t('schedule.type')} rules={[{ required: true, message: t('validation.required', { field: t('schedule.type') }) }]}>
      <Segmented options={[{ value: 'CRON', label: t('schedule.recurring') }, { value: 'ONCE', label: t('schedule.once') }]} />
    </Form.Item>
    {scheduleType === 'CRON' ? <>
      <Form.Item name="schedulePreset" label={t('schedule.frequency')}>
        <Segmented value={preset} onChange={(value) => changePreset(value as SchedulePreset)} options={[
          { value: 'hourly', label: t('schedule.hourly') }, { value: 'daily', label: t('schedule.daily') }, { value: 'weekly', label: t('schedule.weekly') }, { value: 'monthly', label: t('schedule.monthly') }, { value: 'custom', label: t('schedule.custom') },
        ]} />
      </Form.Item>
      <Form.Item name="cronExpression" label={t('form.cron')} rules={[{ required: true, validator: (_, value) => isSixFieldCron(value) ? Promise.resolve() : Promise.reject(new Error(t('validation.cron'))) }]}>
        <Input disabled={preset !== 'custom'} onChange={() => form.setFieldValue('schedulePreset', 'custom')} placeholder={t('schedule.cronPlaceholder')} />
      </Form.Item>
      <Alert type="info" showIcon message={t('schedule.previewHelp')} style={{ marginBottom: 16 }} />
      <Space direction="vertical" size={2} aria-label={t('schedule.previewAria')}>
        {preview.isLoading ? <Typography.Text type="secondary">{t('schedule.previewLoading')}</Typography.Text> : null}
        {(preview.data ?? []).map((instant) => <Typography.Text key={instant}>{formatDateTime(instant, previewDateOptions)}</Typography.Text>)}
      </Space>
    </> : <Space size="large" align="start">
      <Form.Item name="runAtDate" label={t('schedule.runDate')} rules={[{ required: true, message: t('validation.runDate') }]}><DatePicker /></Form.Item>
      <Form.Item name="runAtTime" label={t('schedule.runTime')} rules={[{ required: true, message: t('validation.runTime') }]}><TimePicker format="HH:mm" /></Form.Item>
    </Space>}
  </>;
}
