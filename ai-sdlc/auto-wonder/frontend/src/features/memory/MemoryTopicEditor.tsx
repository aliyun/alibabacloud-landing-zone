import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { Alert, Form, Input, Modal, Select } from 'antd';
import { useEffect, useRef } from 'react';
import type { MemoryDocument, MemoryOwner, MemoryScope, MemoryTopicInput, MemoryType } from './api';

export const memoryTypeLabels: Record<MemoryType, string> = {
  get user() { return i18n.t('memory:types.user'); },
  get feedback() { return i18n.t('memory:types.feedback'); },
  get project() { return i18n.t('memory:types.project'); },
  get reference() { return i18n.t('memory:types.reference'); },
};
export function memoryOwnerName(owner: MemoryOwner): string { return owner.scope === 'ORG' ? i18n.t('memory:owner.organization') : owner.name; }

interface Fields { scope: MemoryScope; ownerRef: number; type: MemoryType; title: string; description: string; contentMd: string }
interface Props { open: boolean; owners: MemoryOwner[]; initialOwner?: MemoryOwner; document?: MemoryDocument; saving: boolean; onCancel: () => void; onSave: (owner: MemoryOwner, topic: MemoryTopicInput) => Promise<void> }

export function MemoryTopicEditor({ open, owners, initialOwner, document, saving, onCancel, onSave }: Props) {
  const { t } = useTranslation('memory');
  const [form] = Form.useForm<Fields>();
  useLocalizedValidation(form, open);
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
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    const owner = owners.find(item => item.scope === values.scope && item.ownerRef === values.ownerRef);
    if (!owner) return;
    const signature = JSON.stringify(values);
    if (request.current.signature !== signature) request.current = { signature, key: `human-${Date.now()}-${Math.random().toString(36).slice(2)}` };
    await onSave(owner, { type: values.type, title: values.title, description: values.description, contentMd: values.contentMd,
      path: document?.path, expectedVersion: document?.version, idempotencyKey: request.current.key });
  };
  return <Modal closable={{ 'aria-label': t('actions.close') }} title={document ? t('topic.edit') : t('page.create')} open={open} width={720} onCancel={onCancel} onOk={submit} confirmLoading={saving} okText={t('actions.save')} cancelText={t('actions.cancel')}>
    <Alert type="info" showIcon message={t('topic.hint')} style={{ marginBottom: 16 }} />
    <Form form={form} layout="vertical">
      <Form.Item name="scope" label={t('topic.scope')} rules={[{ required: true, message: t('validation.scope') }]}><Select disabled={!!document} onChange={() => form.setFieldValue('ownerRef', undefined)} options={[
        { value: 'ORG', label: t('scope.ORG') }, { value: 'SQUAD', label: t('scope.SQUAD') }, { value: 'AGENT', label: t('scope.AGENT') },
      ]} /></Form.Item>
      <Form.Item name="ownerRef" label={t('topic.owner')} rules={[{ required: true, message: t('validation.owner') }]}><Select disabled={!!document} showSearch optionFilterProp="label" options={owners.filter(owner => owner.scope === scope && owner.canCreate).map(owner => ({ value: owner.ownerRef, label: t('owner.option', { squads: owner.squadNames?.length ? owner.squadNames.join(t('owner.squadSeparator')) + ' · ' : '', name: memoryOwnerName(owner) }) }))} /></Form.Item>
      <Form.Item name="type" label={t('topic.type')} rules={[{ required: true, message: t('validation.type') }]}><Select options={Object.entries(memoryTypeLabels).map(([value, label]) => ({ value, label }))} /></Form.Item>
      <Form.Item name="title" label={t('topic.title')} rules={[{ required: true, message: t('validation.title'), whitespace: true }]}><Input maxLength={256} /></Form.Item>
      <Form.Item name="description" label={t('topic.description')} rules={[{ required: true, message: t('validation.description'), whitespace: true }]}><Input placeholder={t('topic.descriptionPlaceholder')} /></Form.Item>
      <Form.Item name="contentMd" label={t('topic.body')} rules={[{ required: true, message: t('validation.body'), whitespace: true }]}><Input.TextArea rows={10} /></Form.Item>
    </Form>
  </Modal>;
}
