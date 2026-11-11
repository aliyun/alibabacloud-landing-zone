import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
  Tag,
  Tooltip,
  Typography,
  message} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import {
  CloseOutlined,
  CloseCircleOutlined,
  InfoCircleOutlined,
  ExclamationCircleOutlined,
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
  updateEnvironmentVariable,
} from './api';
import { environmentVariablesQueryKey, useEnvironmentVariables } from './hooks';
import { QODER_PAT_PRESETS } from './presets';
import type { QoderPatPreset } from './presets';
import type { CreateEnvironmentVariableInput, EnvironmentVariable } from './types';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';

const { Text } = Typography;

interface VariableFormValues extends CreateEnvironmentVariableInput {
  replaceValue?: boolean;
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
  const isAdmin = accessLevel === 'ADMIN';
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const query = useEnvironmentVariables();
  const boundaryGeneration = useRef(0);
  const [savingGeneration, setSavingGeneration] = useState<number>();
  const [deleting, setDeleting] = useState<{ generation: number; id: number }>();
  const [editing, setEditing] = useState<EnvironmentVariable | 'create'>();
  const [presetKey, setPresetKey] = useState<QoderPatPreset['key']>();
  const [operationError, setOperationError] = useState<{ error: unknown }>();
  const [saveError, setSaveError] = useState<{ error: unknown }>();
  const [form] = Form.useForm<VariableFormValues>();
  useLocalizedValidation(form, Boolean(editing));
  const replaceValue = Form.useWatch('replaceValue', form);
  const preset = QODER_PAT_PRESETS.find((item) => item.key === presetKey);
  const isCreating = editing === 'create';
  const presetNameExists = preset
    ? (query.data ?? []).some((variable) => variable.name.toUpperCase() === preset.name.toUpperCase())
    : false;

  const valueRules = preset ? [
    { required: true, whitespace: true, message: t('patValueRequired') },
    {
      validator: (_: unknown, value: string | undefined) => (
        value && /\s/.test(value.trim())
          ? Promise.reject(new Error(t('patValueWhitespace')))
          : Promise.resolve()
      ),
    },
  ] : undefined;

  useEffect(() => useAuthStore.subscribe((state, previous) => {
    if (state.currentWorkspace?.id !== previous.currentWorkspace?.id
      || state.accessLevel !== previous.accessLevel) {
      boundaryGeneration.current += 1;
    }
  }), []);
  useEffect(() => () => {
    boundaryGeneration.current += 1;
  }, []);

  const showCreate = () => {
    setOperationError(undefined);
    setSaveError(undefined);
    setPresetKey(undefined);
    setEditing('create');
    form.setFieldsValue({ name: '', value: '', description: '', replaceValue: false });
  };

  const selectPreset = (next: QoderPatPreset) => {
    setPresetKey(next.key);
    form.setFieldsValue({ name: next.name, description: t(next.descriptionKey) });
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
    setPresetKey(undefined);
    setSaveError(undefined);
    form.resetFields();
  };

  useEffect(() => {
    setSavingGeneration(undefined);
    setDeleting(undefined);
    setEditing(undefined);
    setPresetKey(undefined);
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
          value: preset ? (values.value ?? '').trim() : values.value ?? '',
          description: values.description,
        });
        if (!stillAuthorizedForFormWorkspace()) {
          await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(formWorkspaceId), exact: true });
          return;
        }
        await queryClient.invalidateQueries({ queryKey: environmentVariablesQueryKey(formWorkspaceId), exact: true });
        if (!stillAuthorizedForFormWorkspace()) return;
        if (preset) {
          message.open({
            type: 'success',
            content: <Space size={4} wrap>
              <span>{t('createdGoMount')}</span>
              <Button type="link" size="small" style={{ padding: 0, height: 'auto' }} onClick={() => navigate('/agents')}>{t('goMount')}</Button>
            </Space>,
          });
        } else {
          message.success(i18n.t('environmentVariables:created'));
        }
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
      message.success(i18n.t('environmentVariables:deleted'));
    } catch (error) {
      if (isCurrentDelete()) {
        setOperationError({ error });
      }
    } finally {
      if (isCurrentDelete()) setDeleting(undefined);
    }
  };

  const refresh = () => {
    void query.refetch();
  };

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <PageHeading title={t('title')} description={t('description')}
        extra={isAdmin && <Button aria-label={t('add')} type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={showCreate}>{t('add')}</Button>} />

      <Alert type="info" showIcon icon={<InfoCircleOutlined aria-hidden="true" />} message={t('pagePatHint')} />


      {operationError && <Alert closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('dismissError') }} type="error" showIcon icon={<CloseCircleOutlined aria-hidden="true" />} message={t('operationFailed')} description={errorMessage(operationError.error, t('deleteFailed'))} onClose={() => setOperationError(undefined)} />}
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
          scroll={{ x: 920 }}
          columns={[
            { title: t('name'), dataIndex: 'name', align: 'left', width: 190, render: (name: string) => <Text code>{name}</Text> },
            {
              title: t('value'),
              width: 280,
              render: (_, variable) => <Text code>{variable.value}</Text>,
            },
            { title: t('notes'), dataIndex: 'description', align: 'left', render: (value: string | null) => value || <Text type="secondary">—</Text> },
            {
              title: <Tooltip title={t('mountStatusHelp')}><span>{t('mountStatus')}</span></Tooltip>,
              key: 'mountStatus',
              align: 'left',
              width: 170,
              render: (_, variable) => {
                const online = variable.onlineMountCount;
                const draft = variable.draftMountCount;
                if (online === undefined || draft === undefined) return <Text type="secondary">{t('mountUnknown')}</Text>;
                if (online > 0) return <Tag color="success">{t('mountLive', { count: online })}</Tag>;
                if (draft > 0) return <Tag color="warning">{t('mountDraftOnly')}</Tag>;
                return <Text type="secondary">{t('mountNone')}</Text>;
              },
            },
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
        title={isCreating ? t('add') : t('edit')}
        open={Boolean(editing)}
        okText={isCreating ? t('create') : t('save')}
        cancelText={t('cancel')}
        confirmLoading={savingGeneration === boundaryGeneration.current}
        okButtonProps={{ 'aria-label': isCreating ? t('create') : t('save') }}
        onOk={() => void save()}
        onCancel={closeEditor}
        destroyOnHidden
      >
        {saveError && <Alert type="error" showIcon icon={<CloseCircleOutlined aria-hidden="true" />} message={t('saveFailedTitle')} description={errorMessage(saveError.error, t('saveFailed'))} style={{ marginTop: 20 }} />}
        {isCreating && <Card size="small" title={t('quickCreate')} style={{ marginTop: 20 }}>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Space wrap>
              {QODER_PAT_PRESETS.map((item) => <Button key={item.key} type={presetKey === item.key ? 'primary' : 'default'}
                aria-pressed={presetKey === item.key} onClick={() => selectPreset(item)}>{t(item.labelKey)}</Button>)}
            </Space>
            {preset && <Text type="secondary">{t(preset.hintKey)}</Text>}
            {preset && <Button type="link" style={{ padding: 0, alignSelf: 'flex-start' }} onClick={() => setPresetKey(undefined)}>{t('presetResetToCustom')}</Button>}
          </Space>
        </Card>}
        <Form form={form} layout="vertical" preserve={false} style={{ marginTop: 20 }}>
          <Form.Item label={t('name')} name="name" extra={preset ? t('presetNameLocked') : undefined} rules={[{ required: true, whitespace: true, message: t('nameRequired') }, { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: t('namePattern') }]}>
            <Input autoComplete="off" placeholder={t('namePlaceholder')} maxLength={128} disabled={Boolean(preset)} />
          </Form.Item>
          {preset && presetNameExists && <Alert type="warning" showIcon icon={<ExclamationCircleOutlined aria-hidden="true" />} message={t('presetExistsWarning', { name: preset.name })} style={{ marginBottom: 20 }} />}
          {!isCreating && <Form.Item name="replaceValue" valuePropName="checked">
            <Checkbox>{t('replaceValue')}</Checkbox>
          </Form.Item>}
          {(isCreating || replaceValue) && <Form.Item label={isCreating ? t('value') : t('newValue')} name="value" rules={valueRules}>
            <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideValue') : t('showValue')}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
              style={{ padding: 0, width: 16, height: 16 }} />} autoComplete="new-password" placeholder={t('valuePlaceholder')} />
          </Form.Item>}
          {!isCreating && !replaceValue && <Alert type="info" showIcon icon={<InfoCircleOutlined aria-hidden="true" />} message={t('unchangedValue')} style={{ marginBottom: 20 }} />}
          <Form.Item label={t('notes')} name="description" rules={[{ max: 512, message: t('descriptionLimit') }]}>
            <Input.TextArea rows={3} placeholder={t('descriptionPlaceholder')} />
          </Form.Item>
        </Form>
        {preset && <Alert
          type="info"
          showIcon
          icon={<InfoCircleOutlined aria-hidden="true" />}
          style={{ marginTop: 20 }}
          message={t('patGuideTitle')}
          description={<Space direction="vertical" size={8}>
            <Text>{t('patGuideValue')}</Text>
            <a href={preset.tokenUrl} target="_blank" rel="noopener noreferrer">{t('patGuideOpenLink')}</a>
            <ol style={{ margin: 0, paddingInlineStart: 20 }}>
              <li>{t('patGuideStepToken')}</li>
              <li>{t('patGuideStepMount')}</li>
              <li>{t('patGuideStepPublish')}</li>
              <li>{t('patGuideStepRestart')}</li>
            </ol>
            <Text type="danger">{t('patGuideRisk')}</Text>
          </Space>}
        />}
      </Modal>
    </Space>
  );
}
