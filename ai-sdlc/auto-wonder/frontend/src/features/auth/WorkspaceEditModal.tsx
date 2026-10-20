import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useEffect } from 'react';
import { Form, Input, Modal, message } from 'antd';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import type { WorkspaceInfo } from '@/shared/types/common';
import { useUpdateWorkspace } from './workspaceLifecycleApi';

const { TextArea } = Input;

interface WorkspaceEditFormValues {
  name: string;
  description?: string;
  background?: string;
}

interface WorkspaceEditModalProps {
  workspace: WorkspaceInfo | null;
  onClose: () => void;
}

export function WorkspaceEditModal({ workspace, onClose }: WorkspaceEditModalProps) {
  const { t } = useTranslation('auth');
  const [form] = Form.useForm<WorkspaceEditFormValues>();
  useLocalizedValidation(form, workspace !== null);
  const { mutateAsync, isPending } = useUpdateWorkspace();

  // F1.3 回显: keyed on the id, not on `workspace` identity — a list refetch hands back a new
  // object every time and would otherwise discard whatever the user has typed so far.
  useEffect(() => {
    if (!workspace) return;
    form.setFieldsValue({
      name: workspace.name,
      description: workspace.description ?? '',
      background: workspace.background ?? '',
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace?.id]);

  const handleSubmit = async () => {
    if (!workspace) return;
    let values: WorkspaceEditFormValues;
    try {
      values = await form.validateFields();
    } catch {
      // antd has already rendered the field-level message; a toast on top would be noise.
      return;
    }
    try {
      await mutateAsync({
        id: workspace.id,
        name: values.name,
        description: values.description ?? null,
        background: values.background ?? null,
        version: workspace.version ?? 0,
      });
      message.success(i18n.t('auth:workspace.updated'));
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === ErrorCodes.ORG_VERSION_CONFLICT) {
        // The list refetch triggered by the failed save is what makes a retry possible with a
        // fresh version, so the hint tells the user to reload rather than to click again.
        message.error(i18n.t('auth:workspace.versionConflict'));
        return;
      }
      message.error(e instanceof ApiError ? e.message : i18n.t('auth:workspace.saveFailed'));
    }
  };

  return (
    <Modal
      title={workspace ? t('workspace.editTitle', { name: workspace.name }) : ''}
      open={workspace !== null}
      okText={t('action.save')}
      cancelText={t('action.cancel')}
      confirmLoading={isPending}
      onCancel={onClose}
      onOk={handleSubmit}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="name"
          label={t('workspace.name')}
          rules={[
            { required: true, whitespace: true, message: t('workspace.nameRequired') },
            { max: 128, message: t('workspace.nameLength') },
          ]}
        >
          <Input placeholder={t('workspace.namePlaceholder')} maxLength={128} />
        </Form.Item>
        <Form.Item
          name="description"
          label={t('workspace.description')}
          rules={[{ max: 512, message: t('workspace.descriptionLength') }]}
        >
          <Input placeholder={t('workspace.descriptionPlaceholder')} maxLength={512} />
        </Form.Item>
        <Form.Item name="background" label={t('workspace.background')}>
          <TextArea placeholder={t('workspace.backgroundPlaceholder')} rows={4} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
