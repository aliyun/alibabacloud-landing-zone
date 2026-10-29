import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { CopyOutlined, CheckOutlined, EyeOutlined, EyeInvisibleOutlined, PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { Alert, Button, Card, Checkbox, Drawer, Form, Input, Popconfirm, Select, Space, Tag, Typography, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents } from '@/features/agent/api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { ImIdentityConflictAlert } from '@/shared/ui/ImIdentityConflictAlert';
import { GroupIdGuide } from '../GroupIdGuide';
import { GroupBindingDetailDrawer, type GroupBindingDetailModel } from '../GroupBindingDetailDrawer';
import {
  deleteFeishuBinding,
  listFeishuBindings,
  saveFeishuBinding,
  type FeishuBinding,
  type FeishuBindingRequest,
} from './feishuApi';

const statusLabelKeys = new Map([['ENABLED', 'statusEnabled'], ['DISABLED', 'statusDisabled']] as const);
const SHARED_MODE = 'SHARED';

export function FeishuBindingPanel() {
  const { t } = useTranslation('integration');
  const runWithAccess = useAccessCommand();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<FeishuBinding>();
  const [detail, setDetail] = useState<FeishuBinding | null>(null);
  const [conflictError, setConflictError] = useState<unknown>(null);
  const [form] = Form.useForm<FeishuBindingRequest>();
  useLocalizedValidation(form, open);
  const bindings = useQuery({ queryKey: ['feishu-bindings'], queryFn: listFeishuBindings, refetchInterval: 15000 });
  const agents = useQuery({ queryKey: ['agents-for-binding'], queryFn: () => listAgents({ page: 1, size: 200 }) });
  const refresh = () => client.invalidateQueries({ queryKey: ['feishu-bindings'] });
  const shared = !record || record.bindingMode === SHARED_MODE;
  function showRecord(value: FeishuBinding) {
    setRecord(value);
    setConflictError(null);
    form.resetFields();
    form.setFieldsValue(value.bindingMode === SHARED_MODE
      ? { groupId: value.groupId ?? '', agentId: value.agentId, status: value.status }
      : { appId: value.appId, agentId: value.agentId, status: value.status });
    setOpen(true);
  }
  const save = useMutation({
    mutationFn: (values: FeishuBindingRequest) => shared
      ? saveFeishuBinding(record?.id, {
        bindingMode: 'SHARED',
        groupId: values.groupId,
        agentId: values.agentId,
        status: values.status,
        version: record?.version,
      })
      : saveFeishuBinding(record?.id, { ...values, version: record?.version }),
    onSuccess: () => {
      // Saving the binding is enough: close the form, refresh the list, never save a second time.
      refresh();
      message.success(i18n.t('integration:feishuSaved'));
      setOpen(false);
      setDetail(null);
    },
    onError: async (error: Error, values: FeishuBindingRequest) => {
      // The create may have committed even though the response was lost; check the submitted
      // group before the user can retry and create a duplicate binding.
      const submittedGroup = values.groupId?.trim();
      if (shared && !record?.id && submittedGroup) {
        try {
          const rows = await listFeishuBindings();
          const existing = rows.find((row) => row.bindingMode === SHARED_MODE
            && row.groupId === submittedGroup);
          if (existing) {
            message.warning(i18n.t('integration:existingBindingOpened'));
            setOpen(false);
            setDetail(existing);
            return;
          }
        } catch {
          // The lookup failed too; fall back to the original error so the user stays in the form.
        }
      }
      setConflictError(error);
      message.error(error.message || i18n.t('integration:saveFailed'));
    },
  });
  const remove = useMutation({
    mutationFn: deleteFeishuBinding,
    onSuccess: () => {
      setDetail(null);
      refresh();
      message.success(i18n.t('integration:bindingDeleted'));
    },
    onError: (error: Error) => message.error(error.message || i18n.t('integration:deleteFailed')),
  });
  function edit(value?: FeishuBinding) {
    runWithAccess('ADMIN', value ? t('editFeishu') : t('newFeishu'), () => {
      setDetail(null);
      if (value) {
        showRecord(value);
        return;
      }
      setRecord(undefined); form.resetFields();
      form.setFieldsValue({ status: 'ENABLED' });
      setOpen(true);
    });
  }
  function submit() {
    runWithAccess('ADMIN', t('saveFeishu'), async () => {
      try { const values = await form.validateFields(); save.mutate(values); } catch { /* Form shows validation errors. */ }
    });
  }
  function agentLabel(id: number) {
    const agent = agents.data?.find((item) => item.id === id);
    return agent ? agent.name : `#${id}`;
  }
  function toDetailModel(value: FeishuBinding): GroupBindingDetailModel {
    return {
      bindingMode: value.bindingMode,
      groupId: value.bindingMode === SHARED_MODE ? value.groupId : null,
      agentLabel: agentLabel(value.agentId),
      robotIdentity: value.appId,
      transportLabel: t('httpCallback'),
      enabled: value.status === 'ENABLED',
      diagnostics: [
        { label: t('lastProcessed'), value: value.lastSuccessAt ? formatDateTime(value.lastSuccessAt, { dateStyle: 'medium', timeStyle: 'medium' }) : t('noHistory') },
        { label: t('lastError'), value: value.lastError || t('notAvailable') },
      ],
    };
  }
  const callbackUrl = record?.callbackUrl?.startsWith('/') ? `${window.location.origin}${record.callbackUrl}` : record?.callbackUrl;
  return <Card title={t('feishuTitle')} extra={
    <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} aria-label={t('newFeishu')} onClick={() => edit()}>{t('newFeishu')}</Button>
  }>
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Alert type="info" showIcon message={t('feishuBot')}
      description={t('feishuDescription')} />
    {bindings.isError && <Alert type="error" message={t('feishuFailed')} description={(bindings.error as Error).message} action={<Button onClick={() => bindings.refetch()}>{t('retry')}</Button>} />}
    <Table<FeishuBinding> rowKey="id" dataSource={bindings.data ?? []} loading={bindings.isLoading}
      locale={{ emptyText: t('feishuEmpty') }} scroll={{ x: 900 }} columns={[
        { title: t('groupIdOrMode'), key: 'group', width: 260, render: (_, row) => (
          <Space direction="vertical" size={2} style={{ minWidth: 0, maxWidth: '100%' }}>
            <EllipsisText tooltip={row.bindingMode === SHARED_MODE ? row.groupId ?? t('notAvailable') : row.appId} style={{ maxWidth: 250 }}>
              <Typography.Text code>{row.bindingMode === SHARED_MODE ? (row.groupId || t('notAvailable')) : row.appId}</Typography.Text>
            </EllipsisText>
            <Tag color={row.bindingMode === SHARED_MODE ? 'blue' : 'default'} style={{ marginInlineEnd: 0 }}>
              {row.bindingMode === SHARED_MODE ? t('sharedMode') : t('legacyMode')}
            </Tag>
          </Space>
        ) },
        { title: t('agent'), dataIndex: 'agentId', width: 220, render: (id: number) => {
          const label = agentLabel(id);
          return <EllipsisText tooltip={label}>{label}</EllipsisText>;
        } },
        { title: t('status'), dataIndex: 'status', width: 100, render: (status: FeishuBinding['status']) => {
          const key = statusLabelKeys.get(status);
          return <Tag color={status === 'ENABLED' ? 'green' : 'default'}>{key ? t(key) : status}</Tag>;
        } },
        { title: t('actions'), key: 'actions', width: 180, fixed: 'right', render: (_, row) => <Space size={4}>
          <Button type="link" size="small" onClick={() => setDetail(row)}>{t('detail')}</Button>
          <Button type="link" size="small" onClick={() => edit(row)}>{t('edit')}</Button>
          <Popconfirm
            title={t('confirmDeleteBinding')}
            description={t('deleteHelp')}
            okText={t('deleteBinding')}
            cancelText={t('cancel')}
            okButtonProps={{ danger: true }}
            onConfirm={() => runWithAccess('ADMIN', t('deleteBinding'), () => remove.mutate(row.id))}
          >
            <Button type="link" size="small" danger>{t('delete')}</Button>
          </Popconfirm>
        </Space> },
      ]} />
    <Drawer closable={{ 'aria-label': t('close') }} title={record ? t('editFeishu') : t('newFeishu')} open={open} onClose={() => setOpen(false)} width={560}
      extra={<Button type="primary" aria-label={t('save')} loading={save.isPending} onClick={submit}>{t('save')}</Button>}>
      <Form form={form} layout="vertical">
        {shared ? <>
          <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('sharedBindingHint')} />
          <GroupIdGuide provider="FEISHU" />
          <Form.Item name="groupId" label={t('groupChatGroupId')} rules={[{ required: true, whitespace: true, message: t('requiredGroupId') }]}>
            <Input placeholder={t('groupIdPlaceholder')} maxLength={256} />
          </Form.Item>
          <Form.Item name="agentId" label={t('linkedAgent')} rules={[{ required: true, message: t('selectAgent') }]}>
            <Select showSearch optionFilterProp="label" loading={agents.isLoading} options={(agents.data ?? []).map(a => ({ label: a.name, value: a.id }))} />
          </Form.Item>
          <Form.Item name="status" label={t('bindingStatus')}><Select options={[{ label: t('enabled'), value: 'ENABLED' }, { label: t('disabled'), value: 'DISABLED' }]} /></Form.Item>
        </> : <>
          <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('legacyEditHint')} />
          <Form.Item name="appId" label={t('feishuAppId')} rules={[{ required: true, pattern: /^cli_[A-Za-z0-9]+$/, message: t('validAppId') }]}>
            <Input disabled placeholder="cli_xxxxxxxxx" maxLength={128} />
          </Form.Item>
          <Form.Item name="appSecret" label={t('feishuAppSecret')} extra={t('keepSecret')} rules={[{ required: false, whitespace: true, message: t('requiredFeishuSecret') }]}>
            <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')}
                  icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                  style={{ padding: 0, width: 16, height: 16 }} />} autoComplete="new-password" maxLength={512} />
          </Form.Item>
          <Form.Item name="verificationToken" label={t('verificationToken')} extra={t('keepToken')} rules={[{ required: false, whitespace: true, message: t('requiredToken') }]}>
            <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')}
                  icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                  style={{ padding: 0, width: 16, height: 16 }} />} autoComplete="new-password" maxLength={512} />
          </Form.Item>
          <Form.Item name="encryptKey" label={t('encryptKey')} extra={record?.encryptKeyConfigured ? t('keepEncryption') : t('encryptionHelp')}>
            <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')}
                  icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                  style={{ padding: 0, width: 16, height: 16 }} />} autoComplete="new-password" maxLength={512} />
          </Form.Item>
          {record?.encryptKeyConfigured && <Form.Item name="clearEncryptKey" valuePropName="checked"><Checkbox>{t('clearEncryption')}</Checkbox></Form.Item>}
          <Form.Item name="agentId" label={t('linkedAgent')} rules={[{ required: true, message: t('selectAgent') }]}>
            <Select showSearch optionFilterProp="label" loading={agents.isLoading} options={(agents.data ?? []).map(a => ({ label: a.name, value: a.id }))} />
          </Form.Item>
          <Form.Item name="status" label={t('bindingStatus')}><Select options={[{ label: t('enabled'), value: 'ENABLED' }, { label: t('disabled'), value: 'DISABLED' }]} /></Form.Item>
        </>}
        {agents.isError && <Alert type="error" message={t('agentsFailed')} />}
        {conflictError != null && <div style={{ marginTop: 16 }}><ImIdentityConflictAlert error={conflictError} /></div>}
      </Form>
      {/* AntD caches copy controls with string children; React text keeps labels reactive without remounting. */}
      {!shared && record && <Alert style={{ marginTop: 16 }} type="success" message={t('feishuCallback')} description={<Typography.Paragraph copyable={{ text: callbackUrl, tooltips: [t('copyCallback'), t('copied')], icon: [<CopyOutlined aria-hidden="true" />, <CheckOutlined aria-hidden="true" />] }} style={{ wordBreak: 'break-all', marginBottom: 0 }}>
        <Typography.Text>{callbackUrl}</Typography.Text>
      </Typography.Paragraph>} />}
      <Typography.Paragraph style={{ marginTop: 16 }}>{t('feishuSetup')}</Typography.Paragraph>
      <Typography.Link href="https://open.feishu.cn/document/server-docs/im-v1/message/events/receive" target="_blank" rel="noreferrer">{t('feishuDocs')}</Typography.Link>
    </Drawer>
    <GroupBindingDetailDrawer
      open={detail !== null}
      model={detail ? toDetailModel(detail) : null}
      onClose={() => setDetail(null)}
      onEdit={() => { if (detail) edit(detail); }}
      onDelete={() => { if (detail) remove.mutate(detail.id); }}
      deleteLoading={remove.isPending}
    />
    </Space>
  </Card>;
}
