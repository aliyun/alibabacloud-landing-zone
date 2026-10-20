import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Typography,
  message} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import {
  CloseOutlined,
  CloseCircleOutlined,
  InfoCircleOutlined,
  ExclamationCircleOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import {
  createEnvironmentVariable,
  deleteEnvironmentVariable,
  revealEnvironmentVariableValue,
  updateEnvironmentVariable,
} from './api';
import { environmentVariablesQueryKey, useEnvironmentVariables } from './hooks';
import type { CreateEnvironmentVariableInput, EnvironmentVariable } from './types';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

const { Text } = Typography;

interface VariableFormValues extends CreateEnvironmentVariableInput {
  replaceValue?: boolean;
}

interface RevealedValues {
  boundary: string;
  values: Record<number, string>;
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError || error instanceof Error ? error.message : fallback;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatDateTime(date, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric', hour12: false });
}

export function EnvironmentVariablesPage() {
  const { t } = useTranslation('environmentVariables');
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  const accessLevel = useAuthStore((state) => state.accessLevel);
  const revealBoundary = `${workspaceId ?? 'none'}:${accessLevel ?? 'none'}`;
  const isAdmin = accessLevel === 'ADMIN';
  const queryClient = useQueryClient();
  const query = useEnvironmentVariables();
  const [revealed, setRevealed] = useState<RevealedValues>({ boundary: revealBoundary, values: {} });
  const visibleValues = revealed.boundary === revealBoundary ? revealed.values : {};
  const [revealingId, setRevealingId] = useState<number>();
  const revealGeneration = useRef(0);
  const boundaryGeneration = useRef(0);
  const [savingGeneration, setSavingGeneration] = useState<number>();
  const [deleting, setDeleting] = useState<{ generation: number; id: number }>();
  const [editing, setEditing] = useState<EnvironmentVariable | 'create'>();
  const [operationError, setOperationError] = useState<{ error: unknown; action: 'reveal' | 'delete' }>();
  const [saveError, setSaveError] = useState<{ error: unknown }>();
  const [form] = Form.useForm<VariableFormValues>();
  useLocalizedValidation(form, Boolean(editing));
  const replaceValue = Form.useWatch('replaceValue', form);

  const clearRevealed = () => {
    revealGeneration.current += 1;
    setRevealed({ boundary: revealBoundary, values: {} });
    setRevealingId(undefined);
  };

  useEffect(() => clearRevealed(), [query.dataUpdatedAt, workspaceId]);
  useEffect(() => useAuthStore.subscribe((state, previous) => {
    if (state.currentWorkspace?.id !== previous.currentWorkspace?.id
      || state.accessLevel !== previous.accessLevel) {
      boundaryGeneration.current += 1;
      revealGeneration.current += 1;
    }
  }), []);
  useEffect(() => () => {
    revealGeneration.current += 1;
    boundaryGeneration.current += 1;
  }, []);

  const showCreate = () => {
    setOperationError(undefined);
    setSaveError(undefined);
    setEditing('create');
    form.setFieldsValue({ name: '', value: '', description: '', replaceValue: false });
  };

  const showEdit = (variable: EnvironmentVariable) => {
    setOperationError(undefined);
    setSaveError(undefined);
    setEditing(variable);
    form.setFieldsValue({
      name: variable.name,
      description: variable.description ?? '',
      value: '',
      replaceValue: false,
    });
  };

  const closeEditor = () => {
    setEditing(undefined);
    setSaveError(undefined);
    form.resetFields();
  };

  useEffect(() => {
    setRevealed({ boundary: revealBoundary, values: {} });
    setRevealingId(undefined);
    setSavingGeneration(undefined);
    setDeleting(undefined);
    setEditing(undefined);
    setOperationError(undefined);
    setSaveError(undefined);
    form.resetFields();
  }, [accessLevel, form, workspaceId]);

  const save = async () => {
    const generation = boundaryGeneration.current;
    const formWorkspaceId = workspaceId;
    const stillAuthorizedForFormWorkspace = () => {
      const auth = useAuthStore.getState();
      return generation === boundaryGeneration.current
        && formWorkspaceId !== undefined
        && auth.hasAccess('ADMIN')
        && auth.accessLevel === accessLevel
        && auth.currentWorkspace?.id === formWorkspaceId;
    };
    if (!stillAuthorizedForFormWorkspace()) {
      closeEditor();
      return;
    }
    setSaveError(undefined);
    let values: VariableFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (!stillAuthorizedForFormWorkspace()) {
      return;
    }
    setSavingGeneration(generation);
    try {
      if (editing === 'create') {
        await createEnvironmentVariable({
          name: values.name,
          value: values.value ?? '',
          description: values.description,
        });
        if (!stillAuthorizedForFormWorkspace()) {
          await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(formWorkspaceId), exact: true });
          return;
        }
        await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(formWorkspaceId), exact: true });
        if (!stillAuthorizedForFormWorkspace()) return;
        message.success(i18n.t('environmentVariables:created'));
      } else if (editing) {
        const input = values.replaceValue
          ? { name: values.name, description: values.description, updateValue: true, value: values.value ?? '' }
          : { name: values.name, description: values.description, updateValue: false };
        await updateEnvironmentVariable(editing.id, input);
        if (!stillAuthorizedForFormWorkspace()) {
          await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(formWorkspaceId), exact: true });
          return;
        }
        await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(formWorkspaceId), exact: true });
        if (!stillAuthorizedForFormWorkspace()) return;
        setRevealed((current) => {
          if (current.boundary !== revealBoundary) return current;
          const next = { ...current.values };
          delete next[editing.id];
          return { boundary: revealBoundary, values: next };
        });
        message.success(i18n.t('environmentVariables:updated'));
      }
      closeEditor();
    } catch (error) {
      if (stillAuthorizedForFormWorkspace()) {
        setSaveError({ error });
      }
    } finally {
      if (stillAuthorizedForFormWorkspace()) setSavingGeneration(undefined);
    }
  };

  const reveal = async (variable: EnvironmentVariable) => {
    const generation = revealGeneration.current;
    const boundary = boundaryGeneration.current;
    const revealWorkspaceId = workspaceId;
    const revealAccessLevel = accessLevel;
    const isCurrentReveal = () => {
      const auth = useAuthStore.getState();
      return generation === revealGeneration.current
        && boundary === boundaryGeneration.current
        && auth.currentWorkspace?.id === revealWorkspaceId
        && auth.accessLevel === revealAccessLevel;
    };
    setRevealingId(variable.id);
    setOperationError(undefined);
    try {
      const value = await revealEnvironmentVariableValue(variable.id);
      if (isCurrentReveal()) {
        setRevealed((current) => ({
          boundary: revealBoundary,
          values: current.boundary === revealBoundary
            ? { ...current.values, [variable.id]: value }
            : { [variable.id]: value },
        }));
      }
    } catch (error) {
      if (isCurrentReveal()) {
        setOperationError({ error, action: 'reveal' });
      }
    } finally {
      if (isCurrentReveal()) setRevealingId(undefined);
    }
  };

  const hide = (id: number) => {
    setRevealed((current) => {
      if (current.boundary !== revealBoundary) {
        return { boundary: revealBoundary, values: {} };
      }
      const next = { ...current.values };
      delete next[id];
      return { boundary: revealBoundary, values: next };
    });
  };

  const copy = async (variable: EnvironmentVariable) => {
    const auth = useAuthStore.getState();
    if (auth.currentWorkspace?.id !== workspaceId || auth.accessLevel !== accessLevel) return;
    const value = visibleValues[variable.id];
    if (value === undefined) return;
    if (await copyTextToClipboard(value)) {
      message.success(i18n.t('environmentVariables:copied'));
    } else {
      message.error(i18n.t('environmentVariables:copyFailed'));
    }
  };

  const remove = async (variable: EnvironmentVariable) => {
    const generation = boundaryGeneration.current;
    const deleteWorkspaceId = workspaceId;
    const deleteAccessLevel = accessLevel;
    const isCurrentDelete = () => {
      const auth = useAuthStore.getState();
      return generation === boundaryGeneration.current
        && deleteWorkspaceId !== undefined
        && deleteAccessLevel === 'ADMIN'
        && auth.currentWorkspace?.id === deleteWorkspaceId
        && auth.accessLevel === deleteAccessLevel;
    };
    if (!isCurrentDelete()) return;
    setOperationError(undefined);
    setDeleting({ generation, id: variable.id });
    try {
      await deleteEnvironmentVariable(variable.id);
      if (!isCurrentDelete()) {
        await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(deleteWorkspaceId), exact: true });
        return;
      }
      await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(deleteWorkspaceId), exact: true });
      if (!isCurrentDelete()) return;
      hide(variable.id);
      message.success(i18n.t('environmentVariables:deleted'));
    } catch (error) {
      if (isCurrentDelete()) {
        setOperationError({ error, action: 'delete' });
      }
    } finally {
      if (isCurrentDelete()) setDeleting(undefined);
    }
  };

  const refresh = () => {
    clearRevealed();
    void query.refetch();
  };

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <PageHeading title={t('title')} description={t('description')}
        extra={isAdmin && <Button aria-label={t('add')} type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={showCreate}>{t('add')}</Button>} />


      {operationError && <Alert closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('dismissError') }} type="error" showIcon icon={<CloseCircleOutlined aria-hidden="true" />} message={t('operationFailed')} description={errorMessage(operationError.error, t(operationError.action === 'reveal' ? 'revealFailed' : 'deleteFailed'))} onClose={() => setOperationError(undefined)} />}
      {query.isError && <Alert type="error" showIcon icon={<CloseCircleOutlined aria-hidden="true" />} message={t('loadFailed')} description={errorMessage(query.error, t('retryLater'))} />}

      <Card styles={{ body: { padding: 0 } }}>
        <div style={{ padding: '16px 16px 8px', textAlign: 'right' }}>
          <Button aria-label={t('refresh')} icon={<ReloadOutlined aria-hidden="true" />} loading={query.isFetching} onClick={refresh}>{t('refresh')}</Button>
        </div>
        <Table<EnvironmentVariable>
          rowKey="id"
          size="middle"
          loading={{ spinning: query.isLoading, tip: t('loading') }}
          dataSource={query.data ?? []}
          pagination={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} /> }}
          scroll={{ x: 760 }}
          columns={[
            { title: t('name'), dataIndex: 'name', align: 'left', width: 190, render: (name: string) => <Text code>{name}</Text> },
            {
              title: t('value'),
              width: 280,
              render: (_, variable) => {
                const value = visibleValues[variable.id];
                const visible = value !== undefined;
                return <Space size={4}>
                  <Text code style={{ maxWidth: 190 }} ellipsis={{ tooltip: visible ? value : undefined }}>{visible ? value : '**'}</Text>
                  <Button
                    type="text"
                    size="small"
                    icon={visible ? <EyeInvisibleOutlined aria-hidden="true" /> : <EyeOutlined aria-hidden="true" />}
                    loading={revealingId === variable.id}
                    aria-label={t(visible ? 'hideNamed' : 'showNamed', { name: variable.name })}
                    onClick={() => visible ? hide(variable.id) : void reveal(variable)}
                  />
                  {visible && <Button type="text" size="small" icon={<CopyOutlined aria-hidden="true" />} aria-label={t('copyNamed', { name: variable.name })} onClick={() => void copy(variable)} />}
                </Space>;
              },
            },
            { title: t('notes'), dataIndex: 'description', align: 'left', render: (value: string | null) => value || <Text type="secondary">—</Text> },
            { title: t('updatedAt'), dataIndex: 'gmtModified', width: 180, render: formatDate },
            ...(isAdmin ? [{
              title: t('actions'),
              key: 'actions',
              width: 130,
              render: (_: unknown, variable: EnvironmentVariable) => <Space size={4}>
                <Button type="text" size="small" icon={<EditOutlined aria-hidden="true" />} aria-label={t('editNamed', { name: variable.name })} onClick={() => showEdit(variable)} />
                <Popconfirm
                  icon={<ExclamationCircleOutlined aria-hidden="true" />}
                  title={t('confirmDeleteNamed', { name: variable.name })}
                  description={t('irreversible')}
                  okText={t('confirmDelete')}
                  cancelText={t('cancel')}
                  okButtonProps={{
                    danger: true,
                    loading: deleting?.generation === boundaryGeneration.current && deleting.id === variable.id,
                    'aria-label': t('confirmDelete'),
                  }}
                  onConfirm={() => remove(variable)}
                >
                  <Button danger type="text" size="small" icon={<DeleteOutlined aria-hidden="true" />} aria-label={t('deleteNamed', { name: variable.name })} />
                </Popconfirm>
              </Space>,
            }] : []),
          ]}
        />
      </Card>

      <Modal
        closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('close') }}
        title={editing === 'create' ? t('add') : t('edit')}
        open={Boolean(editing)}
        okText={editing === 'create' ? t('create') : t('save')}
        cancelText={t('cancel')}
        confirmLoading={savingGeneration === boundaryGeneration.current}
        okButtonProps={{ 'aria-label': editing === 'create' ? t('create') : t('save') }}
        onOk={() => void save()}
        onCancel={closeEditor}
        destroyOnHidden
      >
        {saveError && <Alert type="error" showIcon icon={<CloseCircleOutlined aria-hidden="true" />} message={t('saveFailedTitle')} description={errorMessage(saveError.error, t('saveFailed'))} style={{ marginTop: 20 }} />}
        <Form form={form} layout="vertical" preserve={false} style={{ marginTop: 20 }}>
          <Form.Item label={t('name')} name="name" rules={[{ required: true, whitespace: true, message: t('nameRequired') }, { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: t('namePattern') }]}>
            <Input autoComplete="off" placeholder={t('namePlaceholder')} maxLength={128} />
          </Form.Item>
          {editing !== 'create' && <Form.Item name="replaceValue" valuePropName="checked">
            <Checkbox>{t('replaceValue')}</Checkbox>
          </Form.Item>}
          {(editing === 'create' || replaceValue) && <Form.Item label={editing === 'create' ? t('value') : t('newValue')} name="value">
            <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideValue') : t('showValue')}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
              style={{ padding: 0, width: 16, height: 16 }} />} autoComplete="new-password" placeholder={t('valuePlaceholder')} />
          </Form.Item>}
          {editing !== 'create' && !replaceValue && <Alert type="info" showIcon icon={<InfoCircleOutlined aria-hidden="true" />} message={t('unchangedValue')} style={{ marginBottom: 20 }} />}
          <Form.Item label={t('notes')} name="description" rules={[{ max: 512, message: t('descriptionLimit') }]}>
            <Input.TextArea rows={3} placeholder={t('descriptionPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
