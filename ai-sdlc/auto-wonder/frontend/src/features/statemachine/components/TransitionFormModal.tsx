import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { Modal, Form, Select, Input } from 'antd';
import type { StatusNode, StatusTransition } from '../types';

interface Props {
  open: boolean;
  editing: StatusTransition | null;
  nodes: StatusNode[];
  onSubmit: (values: { fromNodeId: number; toNodeId: number; name: string }) => void;
  onCancel: () => void;
  loading?: boolean;
}

export function TransitionFormModal({ open, editing, nodes, onSubmit, onCancel, loading }: Props) {
  const { t } = useTranslation('statemachine');
  const [form] = Form.useForm();
  useLocalizedValidation(form, open);

  const nodeOptions = nodes.map((n) => ({ value: n.id, label: `${n.name} (${n.code})` }));

  const handleOpen = () => {
    if (editing) {
      form.setFieldsValue({ fromNodeId: editing.fromNodeId, toNodeId: editing.toNodeId, name: editing.name });
    } else {
      form.resetFields();
    }
  };

  return (
    <Modal
      closable={{ 'aria-label': t('common.close') }}
      title={editing ? t('transitions.editTitle') : t('transitions.add')}
      open={open}
      onOk={() => form.validateFields().then(onSubmit, () => {})}
      onCancel={onCancel}
      confirmLoading={loading}
      afterOpenChange={(visible) => { if (visible) handleOpen(); }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="fromNodeId" label={t('transitions.from')} rules={[{ required: true, message: t('transitions.fromRequired') }]}>
          <Select options={nodeOptions} placeholder={t('transitions.fromPlaceholder')} />
        </Form.Item>
        <Form.Item name="toNodeId" label={t('transitions.to')} rules={[{ required: true, message: t('transitions.toRequired') }]}>
          <Select options={nodeOptions} placeholder={t('transitions.toPlaceholder')} />
        </Form.Item>
        <Form.Item name="name" label={t('transitions.name')} rules={[{ required: true, message: t('transitions.nameRequired') }]}
          tooltip={t('transitions.nameHelp')}>
          <Input placeholder={t('transitions.namePlaceholder')} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
