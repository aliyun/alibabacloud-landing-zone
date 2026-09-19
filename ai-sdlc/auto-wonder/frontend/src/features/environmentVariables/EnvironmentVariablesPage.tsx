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
  Table,
  Typography,
  message,
} from 'antd';
import {
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

const { Paragraph, Text, Title } = Typography;

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
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

export function EnvironmentVariablesPage() {
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
  const [operationError, setOperationError] = useState<string>();
  const [saveError, setSaveError] = useState<string>();
  const [form] = Form.useForm<VariableFormValues>();
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
        message.success('环境变量已创建');
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
        message.success('环境变量已更新');
      }
      closeEditor();
    } catch (error) {
      if (stillAuthorizedForFormWorkspace()) {
        setSaveError(errorMessage(error, '保存失败，请重试'));
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
        setOperationError(errorMessage(error, '查看环境变量值失败'));
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
      message.success('已复制');
    } else {
      message.error('复制失败，请手动复制');
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
      message.success('环境变量已删除');
    } catch (error) {
      if (isCurrentDelete()) {
        setOperationError(errorMessage(error, '删除失败，请重试'));
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
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start' }}>
        <div>
          <Title level={3} style={{ margin: 0 }}>环境变量</Title>
          <Paragraph type="secondary" style={{ margin: '6px 0 0' }}>
            集中管理数字员工运行时使用的敏感配置。变量值默认隐藏，仅在需要时按项查看。
          </Paragraph>
        </div>
        {isAdmin && <Button aria-label="新增环境变量" type="primary" icon={<PlusOutlined />} onClick={showCreate}>新增环境变量</Button>}
      </div>

      {operationError && <Alert closable type="error" showIcon message="操作未完成" description={operationError} onClose={() => setOperationError(undefined)} />}
      {query.isError && <Alert type="error" showIcon message="环境变量加载失败" description={errorMessage(query.error, '请稍后重试')} />}

      <Card styles={{ body: { padding: 0 } }}>
        <div style={{ padding: '16px 16px 8px', textAlign: 'right' }}>
          <Button aria-label="刷新" icon={<ReloadOutlined />} loading={query.isFetching} onClick={refresh}>刷新</Button>
        </div>
        <Table<EnvironmentVariable>
          rowKey="id"
          size="middle"
          loading={query.isLoading}
          dataSource={query.data ?? []}
          pagination={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无环境变量" /> }}
          scroll={{ x: 760 }}
          columns={[
            { title: '名称', dataIndex: 'name', width: 190, render: (name: string) => <Text code>{name}</Text> },
            {
              title: '值',
              width: 280,
              render: (_, variable) => {
                const value = visibleValues[variable.id];
                const visible = value !== undefined;
                return <Space size={4}>
                  <Text code style={{ maxWidth: 190 }} ellipsis={{ tooltip: visible ? value : undefined }}>{visible ? value : '**'}</Text>
                  <Button
                    type="text"
                    size="small"
                    icon={visible ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                    loading={revealingId === variable.id}
                    aria-label={`${visible ? '隐藏' : '显示'} ${variable.name} 的值`}
                    onClick={() => visible ? hide(variable.id) : void reveal(variable)}
                  />
                  {visible && <Button type="text" size="small" icon={<CopyOutlined />} aria-label={`复制 ${variable.name} 的值`} onClick={() => void copy(variable)} />}
                </Space>;
              },
            },
            { title: '说明', dataIndex: 'description', render: (value: string | null) => value || <Text type="secondary">—</Text> },
            { title: '更新时间', dataIndex: 'gmtModified', width: 180, render: formatDate },
            ...(isAdmin ? [{
              title: '操作',
              key: 'actions',
              width: 130,
              render: (_: unknown, variable: EnvironmentVariable) => <Space size={4}>
                <Button type="text" size="small" icon={<EditOutlined />} aria-label={`编辑 ${variable.name}`} onClick={() => showEdit(variable)} />
                <Popconfirm
                  title={`确认删除环境变量 ${variable.name}？`}
                  description="删除后无法恢复。"
                  okText="确认删除"
                  cancelText="取消"
                  okButtonProps={{
                    danger: true,
                    loading: deleting?.generation === boundaryGeneration.current && deleting.id === variable.id,
                    'aria-label': '确认删除',
                  }}
                  onConfirm={() => remove(variable)}
                >
                  <Button danger type="text" size="small" icon={<DeleteOutlined />} aria-label={`删除 ${variable.name}`} />
                </Popconfirm>
              </Space>,
            }] : []),
          ]}
        />
      </Card>

      <Modal
        title={editing === 'create' ? '新增环境变量' : '编辑环境变量'}
        open={Boolean(editing)}
        okText={editing === 'create' ? '创建' : '保存'}
        cancelText="取消"
        confirmLoading={savingGeneration === boundaryGeneration.current}
        okButtonProps={{ 'aria-label': editing === 'create' ? '创建' : '保存' }}
        onOk={() => void save()}
        onCancel={closeEditor}
        destroyOnHidden
      >
        {saveError && <Alert type="error" showIcon message="保存失败" description={saveError} style={{ marginTop: 20 }} />}
        <Form form={form} layout="vertical" preserve={false} style={{ marginTop: 20 }}>
          <Form.Item label="名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入名称' }, { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: '使用字母、数字和下划线，且不能以数字开头' }]}>
            <Input autoComplete="off" placeholder="例如 API_TOKEN" maxLength={128} />
          </Form.Item>
          {editing !== 'create' && <Form.Item name="replaceValue" valuePropName="checked">
            <Checkbox>替换当前值</Checkbox>
          </Form.Item>}
          {(editing === 'create' || replaceValue) && <Form.Item label={editing === 'create' ? '值' : '新值'} name="value">
            <Input.Password autoComplete="new-password" placeholder="输入后将加密保存" />
          </Form.Item>}
          {editing !== 'create' && !replaceValue && <Alert type="info" showIcon message="当前值不会被读取或更改" style={{ marginBottom: 20 }} />}
          <Form.Item label="说明" name="description" rules={[{ max: 512, message: '说明不能超过 512 个字符' }]}>
            <Input.TextArea rows={3} placeholder="简要说明用途（可选）" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
