import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { Modal, Form, Input, Select, InputNumber } from 'antd';
import type { StatusNode, NodeCategory } from '../types';

interface Props {
  open: boolean;
  editing: StatusNode | null;
  nextSort: number;
  onSubmit: (values: { code: string; name: string; category: string; sort: number }) => void;
  onCancel: () => void;
  loading?: boolean;
}

export function NodeFormModal({ open, editing, nextSort, onSubmit, onCancel, loading }: Props) {
  const { t } = useTranslation('statemachine');
  const categoryOptions: { value: NodeCategory; label: string }[] = [
    { value: 'INIT', label: t('categories.INIT') },
    { value: 'IN_PROGRESS', label: t('categories.IN_PROGRESS') },
    { value: 'DONE', label: t('categories.DONE') },
    { value: 'CANCELED', label: t('categories.CANCELED') },
  ];
  const [form] = Form.useForm();
  useLocalizedValidation(form, open);

  const handleOpen = () => {
    if (editing) {
      form.setFieldsValue({ code: editing.code, name: editing.name, category: editing.category, sort: editing.sort });
    } else {
      form.resetFields();
      form.setFieldsValue({ category: 'IN_PROGRESS', sort: nextSort });
    }
  };

  return (
    <Modal
      closable={{ 'aria-label': t('common.close') }}
      title={editing ? t('actions.editNode') : t('actions.createNode')}
      open={open}
      onOk={() => form.validateFields().then(onSubmit, () => {})}
      onCancel={onCancel}
      confirmLoading={loading}
      afterOpenChange={(visible) => { if (visible) handleOpen(); }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="code" label={t('nodes.code')} rules={[{ required: true, message: t('nodes.codeRequired') }]}
          tooltip={t('nodes.codeHelp')}>
          <Input placeholder={t('nodes.codePlaceholder')} disabled={!!editing} />
        </Form.Item>
        <Form.Item name="name" label={t('nodes.name')} rules={[{ required: true, message: t('nodes.nameRequired') }]}>
          <Input placeholder={t('nodes.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="category" label={t('nodes.category')} rules={[{ required: true, message: t('nodes.categoryRequired') }]}>
          <Select options={categoryOptions} />
        </Form.Item>
        <Form.Item name="sort" label={t('nodes.sort')} tooltip={t('nodes.sortHelp')}>
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
