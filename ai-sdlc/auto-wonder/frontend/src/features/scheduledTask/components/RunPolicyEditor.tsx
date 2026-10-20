import { useTranslation } from 'react-i18next';
import { Form, InputNumber, Select } from 'antd';
import type { OverlapPolicy, SessionMode } from '../types';

export function normalizeRunPolicy(value: { sessionMode: SessionMode; overlapPolicy: OverlapPolicy; affinityTimeoutSeconds?: number; startDeadlineSeconds?: number }) {
  const continuous = value.sessionMode === 'CONTINUOUS';
  return {
    sessionMode: value.sessionMode,
    overlapPolicy: continuous && value.overlapPolicy === 'ALLOW' ? 'SKIP' as OverlapPolicy : value.overlapPolicy,
    affinityTimeoutSeconds: continuous ? Math.max(1, value.affinityTimeoutSeconds ?? 1) : Math.max(0, value.affinityTimeoutSeconds ?? 0),
    startDeadlineSeconds: Math.max(1, value.startDeadlineSeconds ?? 1),
  };
}

export function RunPolicyEditor() {
  const { t } = useTranslation('scheduledTask');
  const form = Form.useFormInstance();
  const sessionMode = Form.useWatch('sessionMode', form) as SessionMode | undefined;
  const continuous = sessionMode === 'CONTINUOUS';
  const onSessionChange = (next: SessionMode) => {
    const normalized = normalizeRunPolicy({
      sessionMode: next,
      overlapPolicy: form.getFieldValue('overlapPolicy') ?? 'SKIP',
      affinityTimeoutSeconds: form.getFieldValue('affinityTimeoutSeconds'),
      startDeadlineSeconds: form.getFieldValue('startDeadlineSeconds'),
    });
    form.setFieldsValue(normalized);
  };
  return <>
    <Form.Item name="sessionMode" label={t('form.sessionMode')} rules={[{ required: true, message: t('validation.required', { field: t('form.sessionMode') }) }]}>
      <Select onChange={onSessionChange} options={[{ value: 'ISOLATED', label: t('session.isolatedRecommended') }, { value: 'CONTINUOUS', label: t('session.continuous') }]} />
    </Form.Item>
    <Form.Item name="overlapPolicy" label={t('form.overlapExecution')} rules={[{ required: true, message: t('validation.required', { field: t('form.overlapExecution') }) }]}>
      <Select options={[{ value: 'SKIP', label: t('overlap.skip') }, { value: 'QUEUE', label: t('overlap.queue') }, ...(continuous ? [] : [{ value: 'ALLOW', label: t('overlap.allow') }])]} />
    </Form.Item>
    <Form.Item name="misfirePolicy" label={t('form.missedTrigger')} rules={[{ required: true, message: t('validation.required', { field: t('form.missedTrigger') }) }]}>
      <Select options={[{ value: 'SKIP_ALL', label: t('misfire.skipAll') }, { value: 'FIRE_LATEST', label: t('misfire.fireLatest') }, { value: 'FIRE_ALL', label: t('misfire.fireAll') }]} />
    </Form.Item>
    <Form.Item name="startDeadlineSeconds" label={t('form.startDeadline')} rules={[{ required: true, message: t('validation.numberRequired', { field: t('form.startDeadline') }) }]}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item>
    <Form.Item name="affinityTimeoutSeconds" label={t('form.affinityTimeout')} rules={[{ required: true, message: t('validation.numberRequired', { field: t('form.affinityTimeout') }) }]}><InputNumber min={continuous ? 1 : 0} style={{ width: '100%' }} /></Form.Item>
  </>;
}
