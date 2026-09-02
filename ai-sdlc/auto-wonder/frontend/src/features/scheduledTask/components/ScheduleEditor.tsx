import { Alert, DatePicker, Form, Input, Segmented, Space, TimePicker, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import type { ScheduleType } from '../types';
import { previewScheduledTask } from '../api';

export type SchedulePreset = 'hourly' | 'daily' | 'weekly' | 'monthly' | 'custom';
export const PRESET_CRON: Record<Exclude<SchedulePreset, 'custom'>, string> = {
  hourly: '0 0 * * * *', daily: '0 0 2 * * *', weekly: '0 0 2 * * 2', monthly: '0 0 2 1 * *',
};

export function isSixFieldCron(value?: string): boolean {
  if (!value || value.trim().split(/\s+/).length !== 6) return false;
  return value.trim().split(/\s+/).every((part) => /^[0-9*?,/\-LW#]+$/.test(part));
}

export function ScheduleEditor({ timezone = 'Asia/Shanghai' }: { timezone?: string }) {
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
    <Form.Item name="scheduleType" label="调度方式" rules={[{ required: true }]}>
      <Segmented options={[{ value: 'CRON', label: '周期执行' }, { value: 'ONCE', label: '单次执行' }]} />
    </Form.Item>
    {scheduleType === 'CRON' ? <>
      <Form.Item name="schedulePreset" label="执行频率">
        <Segmented value={preset} onChange={(value) => changePreset(value as SchedulePreset)} options={[
          { value: 'hourly', label: '每小时' }, { value: 'daily', label: '每天' }, { value: 'weekly', label: '每周' }, { value: 'monthly', label: '每月' }, { value: 'custom', label: '自定义' },
        ]} />
      </Form.Item>
      <Form.Item name="cronExpression" label="Cron 表达式" rules={[{ required: true, validator: (_, value) => isSixFieldCron(value) ? Promise.resolve() : Promise.reject(new Error('请输入合法的六字段 Cron 表达式')) }]}>
        <Input disabled={preset !== 'custom'} onChange={() => form.setFieldValue('schedulePreset', 'custom')} placeholder="秒 分 时 日 月 星期，例如 0 0 2 * * *" />
      </Form.Item>
      <Alert type="info" showIcon message="下方为服务端 Cron 计算的未来五次执行时间。" style={{ marginBottom: 16 }} />
      <Space direction="vertical" size={2} aria-label="未来执行时间">
        {preview.isLoading ? <Typography.Text type="secondary">正在计算未来执行时间…</Typography.Text> : null}
        {(preview.data ?? []).map((instant) => <Typography.Text key={instant}>{new Date(instant).toLocaleString('zh-CN')}</Typography.Text>)}
      </Space>
    </> : <Space size="large" align="start">
      <Form.Item name="runAtDate" label="执行日期" rules={[{ required: true, message: '请选择单次执行日期' }]}><DatePicker /></Form.Item>
      <Form.Item name="runAtTime" label="执行时间" rules={[{ required: true, message: '请选择单次执行时间' }]}><TimePicker format="HH:mm" /></Form.Item>
    </Space>}
  </>;
}
