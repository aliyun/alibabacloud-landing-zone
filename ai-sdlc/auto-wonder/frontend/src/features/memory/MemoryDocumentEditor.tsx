import { Alert, Button, Form, Input, Modal, Space, Typography } from 'antd';
import { useEffect, useMemo } from 'react';
import type { MemoryDocument, MemoryMutation } from './api';

interface Props { open: boolean; document?: MemoryDocument; writable: boolean; saving: boolean; onCancel: () => void; onSave: (mutation: MemoryMutation) => Promise<void> }
export function memoryDocumentMeasurements(value: string) { return { lines: value.length === 0 ? 0 : value.split(/\r?\n/).length, bytes: new TextEncoder().encode(value).length }; }
function requestKey() { return `console-${Date.now()}-${Math.random().toString(36).slice(2)}`; }

export function MemoryDocumentEditor({ open, document, writable, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm<{ path: string; contentMd: string }>();
  const content = Form.useWatch('contentMd', form) ?? '';
  const path = Form.useWatch('path', form) ?? '';
  const measurements = useMemo(() => memoryDocumentMeasurements(content), [content]);
  const indexOverLimit = path === 'MEMORY.md' && (measurements.lines > 200 || measurements.bytes > 25_000);
  useEffect(() => { if (open) form.setFieldsValue({ path: document?.path ?? '', contentMd: document?.contentMd ?? '' }); }, [document, form, open]);
  const submit = async () => {
    const value = await form.validateFields();
    await onSave(document ? { operation: 'UPDATE', path: document.path, newPath: value.path, contentMd: value.contentMd, expectedVersion: document.version, idempotencyKey: requestKey() }
      : { operation: 'CREATE', path: value.path, newPath: value.path, contentMd: value.contentMd, idempotencyKey: requestKey() });
  };
  return <Modal title={document ? '编辑记忆文档' : '新增记忆文档'} open={open} onCancel={onCancel} width={760}
    footer={<Space><Button onClick={onCancel}>取消</Button><Button type="primary" disabled={!writable} loading={saving} onClick={submit}>保存</Button></Space>}>
    {!writable && <Alert type="info" showIcon message="当前存储为只读" style={{ marginBottom: 12 }} />}
    {indexOverLimit && <Alert type="warning" showIcon message="MEMORY.md 超过 200 行或 25,000 字节；仍可保存，但会话只加载边界内的前缀" style={{ marginBottom: 12 }} />}
    <Form form={form} layout="vertical">
      <Form.Item label="路径" name="path" rules={[{ required: true }, { pattern: /^(?![/.])(?!.*(?:^|\/)\.\.?(?:\/|$))(?!.*\\).*\.md$/, message: '请输入安全的相对 Markdown 路径' }]}><Input placeholder="feedback_testing.md" disabled={!writable} /></Form.Item>
      <Form.Item label="内容" name="contentMd" rules={[{ required: true, whitespace: true }]}><Input.TextArea rows={18} disabled={!writable} /></Form.Item>
    </Form>
    <Typography.Text type={indexOverLimit ? 'danger' : 'secondary'}>{measurements.lines} 行 · {measurements.bytes.toLocaleString()} 字节</Typography.Text>
  </Modal>;
}
