import { Alert, Form, Input, Modal, Select } from 'antd';
import { useEffect, useRef } from 'react';
import type { MemoryDocument, MemoryOwner, MemoryScope, MemoryTopicInput, MemoryType } from './api';

export const memoryTypeLabels: Record<MemoryType, string> = { user: '用户偏好', feedback: '反馈与约定', project: '项目背景', reference: '参考资料' };
interface Fields { scope: MemoryScope; ownerRef: number; type: MemoryType; title: string; description: string; contentMd: string }
interface Props { open: boolean; owners: MemoryOwner[]; initialOwner?: MemoryOwner; document?: MemoryDocument; saving: boolean; onCancel: () => void; onSave: (owner: MemoryOwner, topic: MemoryTopicInput) => Promise<void> }

export function MemoryTopicEditor({ open, owners, initialOwner, document, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm<Fields>();
  const scope = Form.useWatch('scope', form);
  const request = useRef({ signature: '', key: '' });
  const initialized = useRef('');
  useEffect(() => {
    if (!open) { initialized.current = ''; return; }
    const identity = `${initialOwner?.scope}:${initialOwner?.ownerRef}:${document?.id ?? 'new'}`;
    if (initialized.current === identity) return;
    initialized.current = identity;
    form.resetFields();
    form.setFieldsValue({ scope: initialOwner?.scope ?? 'AGENT', ownerRef: initialOwner?.ownerRef,
      type: (document?.memoryType as MemoryType) ?? 'reference', title: document?.title ?? '',
      description: document?.description ?? '', contentMd: document?.body ?? document?.contentMd ?? '' });
    request.current = { signature: '', key: '' };
  }, [open, initialOwner, document, form]);
  const submit = async () => {
    const values = await form.validateFields();
    const owner = owners.find(item => item.scope === values.scope && item.ownerRef === values.ownerRef);
    if (!owner) return;
    const signature = JSON.stringify(values);
    if (request.current.signature !== signature) request.current = { signature, key: `human-${Date.now()}-${Math.random().toString(36).slice(2)}` };
    await onSave(owner, { type: values.type, title: values.title, description: values.description, contentMd: values.contentMd,
      path: document?.path, expectedVersion: document?.version, idempotencyKey: request.current.key });
  };
  return <Modal title={document ? '编辑记忆' : '新增记忆'} open={open} width={720} onCancel={onCancel} onOk={submit} confirmLoading={saving} okText="保存">
    <Alert type="info" showIcon message="归属决定哪些数字人可以使用；保存后自动维护记忆正文和索引，无需审核。" style={{ marginBottom: 16 }} />
    <Form form={form} layout="vertical">
      <Form.Item name="scope" label="归属范围" rules={[{ required: true }]}><Select disabled={!!document} onChange={() => form.setFieldValue('ownerRef', undefined)} options={[
        { value: 'ORG', label: '组织共享' }, { value: 'SQUAD', label: '小队共享' }, { value: 'AGENT', label: '数字人个人' },
      ]} /></Form.Item>
      <Form.Item name="ownerRef" label="所属对象" rules={[{ required: true }]}><Select disabled={!!document} showSearch optionFilterProp="label" options={owners.filter(owner => owner.scope === scope && owner.canCreate).map(owner => ({ value: owner.ownerRef, label: `${owner.squadNames?.length ? owner.squadNames.join('、') + ' · ' : ''}${owner.name}` }))} /></Form.Item>
      <Form.Item name="type" label="记忆类型" rules={[{ required: true }]}><Select options={Object.entries(memoryTypeLabels).map(([value, label]) => ({ value, label }))} /></Form.Item>
      <Form.Item name="title" label="标题" rules={[{ required: true, whitespace: true }]}><Input maxLength={256} /></Form.Item>
      <Form.Item name="description" label="适用说明" rules={[{ required: true, whitespace: true }]}><Input placeholder="在什么任务或情况下值得读取这条记忆" /></Form.Item>
      <Form.Item name="contentMd" label="正文" rules={[{ required: true, whitespace: true }]}><Input.TextArea rows={10} /></Form.Item>
    </Form>
  </Modal>;
}
