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
import { deleteFeishuBinding, listFeishuBindings, saveFeishuBinding, type FeishuBinding, type FeishuBindingRequest } from './feishuApi';

const statusLabelKeys = new Map([['ENABLED', 'statusEnabled'], ['DISABLED', 'statusDisabled']] as const);

export function FeishuBindingPanel() {
  const { t } = useTranslation('integration');
  const runWithAccess = useAccessCommand();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<FeishuBinding>();
  const [form] = Form.useForm<FeishuBindingRequest>();
  useLocalizedValidation(form, open);
  const bindings = useQuery({ queryKey: ['feishu-bindings'], queryFn: listFeishuBindings, refetchInterval: 15000 });
  const agents = useQuery({ queryKey: ['agents-for-binding'], queryFn: () => listAgents({ page: 1, size: 200 }) });
  const refresh = () => client.invalidateQueries({ queryKey: ['feishu-bindings'] });
  const save = useMutation({
    mutationFn: (values: FeishuBindingRequest) => saveFeishuBinding(record?.id, { ...values, version: record?.version }),
    onSuccess: (saved) => {
      setRecord(saved);
      form.resetFields();
      form.setFieldsValue({ appId: saved.appId, agentId: saved.agentId, status: saved.status });
      refresh();
      message.success(i18n.t('integration:feishuSaved'));
    },
    onError: (error: Error) => message.error(error.message || i18n.t('integration:saveFailed')),
  });
  const remove = useMutation({ mutationFn: deleteFeishuBinding, onSuccess: refresh, onError: (error: Error) => message.error(error.message || i18n.t('integration:deleteFailed')) });
  function edit(value?: FeishuBinding) {
    runWithAccess('ADMIN', value ? t('editFeishu') : t('newFeishu'), () => {
      setRecord(value); form.resetFields();
      form.setFieldsValue({ appId: value?.appId, agentId: value?.agentId, status: value?.status ?? 'ENABLED' });
      setOpen(true);
    });
  }
  function submit() {
    runWithAccess('ADMIN', t('saveFeishu'), async () => {
      try { const values = await form.validateFields(); save.mutate(values); } catch { /* Form shows validation errors. */ }
    });
  }
  const callbackUrl = record?.callbackUrl.startsWith('/') ? `${window.location.origin}${record.callbackUrl}` : record?.callbackUrl;
  return <Card title={t('feishuTitle')} extra={
    <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} aria-label={t('newFeishu')} onClick={() => edit()}>{t('newFeishu')}</Button>
  }>
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Alert type="info" showIcon message={t('feishuBot')}
      description={t('feishuDescription')} />
    {bindings.isError && <Alert type="error" message={t('feishuFailed')} description={(bindings.error as Error).message} action={<Button onClick={() => bindings.refetch()}>{t('retry')}</Button>} />}
    <Table<FeishuBinding> rowKey="id" dataSource={bindings.data ?? []} loading={bindings.isLoading}
      locale={{ emptyText: t('feishuEmpty') }} scroll={{ x: 900 }} columns={[
        { title: t('feishuAppId'), dataIndex: 'appId' },
        { title: t('agent'), dataIndex: 'agentId', render: (id: number) => agents.data?.find(a => a.id === id)?.name ?? `#${id}` },
        { title: t('status'), dataIndex: 'status', render: (status: FeishuBinding['status']) => {
          const key = statusLabelKeys.get(status);
          return <Tag color={status === 'ENABLED' ? 'green' : 'default'}>{key ? t(key) : status}</Tag>;
        } },
        { title: t('lastProcessed'), dataIndex: 'lastSuccessAt', render: (date: string | null) => date ? formatDateTime(date, { dateStyle: 'medium', timeStyle: 'medium' }) : t('noHistory') },
        { title: t('lastError'), dataIndex: 'lastError', render: (error: string | null) => error || '—' },
        { title: t('actions'), render: (_, row) => <Space>
          <Button type="link" onClick={() => edit(row)}>{t('edit')}</Button>
          <Popconfirm okText={t('delete')} cancelText={t('cancel')} title={t('confirmFeishuDelete')} description={t('feishuDeleteHelp')} onConfirm={() => runWithAccess('ADMIN', t('deleteFeishu'), () => remove.mutate(row.id))}>
            <Button type="link" danger aria-label={t('delete')} loading={remove.isPending}>{t('delete')}</Button>
          </Popconfirm>
        </Space> },
      ]} />
    <Drawer closable={{ 'aria-label': t('close') }} title={record ? t('editFeishu') : t('newFeishu')} open={open} onClose={() => setOpen(false)} width={560}
      extra={<Button type="primary" aria-label={t('save')} loading={save.isPending} onClick={submit}>{t('save')}</Button>}>
      <Form form={form} layout="vertical">
        <Form.Item name="appId" label={t('feishuAppId')} rules={[{ required: true, pattern: /^cli_[A-Za-z0-9]+$/, message: t('validAppId') }]}>
          <Input disabled={!!record} placeholder="cli_xxxxxxxxx" maxLength={128} />
        </Form.Item>
        <Form.Item name="appSecret" label={t('feishuAppSecret')} extra={record ? t('keepSecret') : undefined} rules={[{ required: !record, whitespace: true, message: t('requiredFeishuSecret') }]}>
          <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')}
                icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                style={{ padding: 0, width: 16, height: 16 }} />} autoComplete="new-password" maxLength={512} />
        </Form.Item>
        <Form.Item name="verificationToken" label={t('verificationToken')} extra={record ? t('keepToken') : t('tokenHelp')} rules={[{ required: !record, whitespace: true, message: t('requiredToken') }]}>
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
        {agents.isError && <Alert type="error" message={t('agentsFailed')} />}
        <Form.Item name="status" label={t('bindingStatus')}><Select options={[{ label: t('enabled'), value: 'ENABLED' }, { label: t('disabled'), value: 'DISABLED' }]} /></Form.Item>
      </Form>
      {/* AntD caches copy controls with string children; React text keeps labels reactive without remounting. */}
      {record && <Alert type="success" message={t('feishuCallback')} description={<Typography.Paragraph copyable={{ text: callbackUrl, tooltips: [t('copyCallback'), t('copied')], icon: [<CopyOutlined aria-hidden="true" />, <CheckOutlined aria-hidden="true" />] }} style={{ wordBreak: 'break-all', marginBottom: 0 }}>
        <Typography.Text>{callbackUrl}</Typography.Text>
      </Typography.Paragraph>} />}
      <Typography.Paragraph style={{ marginTop: 16 }}>{t('feishuSetup')}</Typography.Paragraph>
      <Typography.Link href="https://open.feishu.cn/document/server-docs/im-v1/message/events/receive" target="_blank" rel="noreferrer">{t('feishuDocs')}</Typography.Link>
    </Drawer>
    </Space>
  </Card>;
}
