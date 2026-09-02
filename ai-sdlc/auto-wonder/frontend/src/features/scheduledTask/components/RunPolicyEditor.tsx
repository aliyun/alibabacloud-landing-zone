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
    <Form.Item name="sessionMode" label="会话模式" rules={[{ required: true }]}>
      <Select onChange={onSessionChange} options={[{ value: 'ISOLATED', label: '单次隔离会话（推荐）' }, { value: 'CONTINUOUS', label: '连续会话' }]} />
    </Form.Item>
    <Form.Item name="overlapPolicy" label="重叠执行策略" rules={[{ required: true }]}>
      <Select options={[{ value: 'SKIP', label: '跳过本次' }, { value: 'QUEUE', label: '排队等待' }, ...(continuous ? [] : [{ value: 'ALLOW', label: '允许并行' }])]} />
    </Form.Item>
    <Form.Item name="misfirePolicy" label="错过触发策略" rules={[{ required: true }]}>
      <Select options={[{ value: 'SKIP_ALL', label: '全部跳过' }, { value: 'FIRE_LATEST', label: '补跑最近一次' }, { value: 'FIRE_ALL', label: '全部补跑' }]} />
    </Form.Item>
    <Form.Item name="startDeadlineSeconds" label="启动超时（秒）" rules={[{ required: true }]}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item>
    <Form.Item name="affinityTimeoutSeconds" label="连续会话亲和超时（秒）" rules={[{ required: true }]}><InputNumber min={continuous ? 1 : 0} style={{ width: '100%' }} /></Form.Item>
  </>;
}
