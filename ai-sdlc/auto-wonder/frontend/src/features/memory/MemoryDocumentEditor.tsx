import { formatNumber } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Form, Input, Modal, Space, Typography } from 'antd';
import { useEffect, useMemo } from 'react';
import type { MemoryDocument, MemoryMutation } from './api';

interface Props { open: boolean; document?: MemoryDocument; writable: boolean; saving: boolean; onCancel: () => void; onSave: (mutation: MemoryMutation) => Promise<void> }
export function memoryDocumentMeasurements(value: string) { return { lines: value.length === 0 ? 0 : value.split(/\r?\n/).length, bytes: new TextEncoder().encode(value).length }; }
function requestKey() { return `console-${Date.now()}-${Math.random().toString(36).slice(2)}`; }

export function MemoryDocumentEditor({ open, document, writable, saving, onCancel, onSave }: Props) {
  const { t } = useTranslation('memory');
  const [form] = Form.useForm<{ path: string; contentMd: string }>();
  useLocalizedValidation(form, open);
  const content = Form.useWatch('contentMd', form) ?? '';
  const path = Form.useWatch('path', form) ?? '';
  const measurements = useMemo(() => memoryDocumentMeasurements(content), [content]);
  const indexOverLimit = path === 'MEMORY.md' && (measurements.lines > 200 || measurements.bytes > 25_000);
  useEffect(() => { if (open) form.setFieldsValue({ path: document?.path ?? '', contentMd: document?.contentMd ?? '' }); }, [document, form, open]);
  const submit = async () => {
    const value = await form.validateFields().catch(() => null);
    if (!value) return;
    await onSave(document ? { operation: 'UPDATE', path: document.path, newPath: value.path, contentMd: value.contentMd, expectedVersion: document.version, idempotencyKey: requestKey() }
      : { operation: 'CREATE', path: value.path, newPath: value.path, contentMd: value.contentMd, idempotencyKey: requestKey() });
  };
  return <Modal closable={{ 'aria-label': t('actions.close') }} title={document ? t('editor.edit') : t('editor.create')} open={open} onCancel={onCancel} width={760}
    footer={<Space><Button onClick={onCancel}>{t('actions.cancel')}</Button><Button type="primary" disabled={!writable} loading={saving} onClick={submit}>{t('actions.save')}</Button></Space>}>
    {!writable && <Alert type="info" showIcon message={t('editor.readOnly')} style={{ marginBottom: 12 }} />}
    {indexOverLimit && <Alert type="warning" showIcon message={t('editor.limitWarning')} style={{ marginBottom: 12 }} />}
    <Form form={form} layout="vertical">
      <Form.Item label={t('editor.path')} name="path" rules={[{ required: true, message: t('validation.path') }, { pattern: /^(?![/.])(?!.*(?:^|\/)\.\.?(?:\/|$))(?!.*\\).*\.md$/, message: t('validation.safePath') }]}><Input placeholder={t('editor.pathExample')} disabled={!writable} /></Form.Item>
      <Form.Item label={t('editor.content')} name="contentMd" rules={[{ required: true, message: t('validation.content'), whitespace: true }]}><Input.TextArea rows={18} disabled={!writable} /></Form.Item>
    </Form>
    <Typography.Text type={indexOverLimit ? 'danger' : 'secondary'}>{t('counts.measurements', { lines: t('counts.lines', { count: measurements.lines, value: formatNumber(measurements.lines) }), bytes: t('counts.bytes', { count: measurements.bytes, value: formatNumber(measurements.bytes) }) })}</Typography.Text>
  </Modal>;
}
