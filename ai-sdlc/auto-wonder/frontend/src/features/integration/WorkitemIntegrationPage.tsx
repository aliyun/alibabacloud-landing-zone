import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useMemo, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Select, Space, Tag, Tooltip, Typography, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { EyeOutlined, EyeInvisibleOutlined, ApiOutlined, CloudSyncOutlined, InfoCircleOutlined, SaveOutlined, SearchOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import {
  createAoneBinding,
  dispatchAoneOutbox,
  getIntegrationCapabilities,
  listAoneBindings,
  searchAoneProjects,
  syncAoneNow,
  testAoneConnection,
  type AoneBinding,
  type AoneBindingRequest,
  type AoneTestConnectionResult,
  type AoneSyncResult,
  type ExternalProject,
} from './aoneApi';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import './Integrations.css';

const { Text } = Typography;

type IntegrationForm = AoneBindingRequest & {
  provider: 'AONE';
  projectQuery?: string;
  selectedProjectIds?: string[];
  issueIds?: string;
};

export function WorkitemIntegrationPage() {
  const { t } = useTranslation('integration');
  const { data: capabilities, isLoading } = useQuery({
    queryKey: ['integration-capabilities'],
    queryFn: getIntegrationCapabilities,
  });

  if (isLoading) {
    return <Card loading />;
  }
  if (!capabilities?.aoneEnabled) {
    return <Alert type="info" showIcon message={t('aoneDisabled')} />;
  }
  return <AoneIntegrationPanel />;
}

function AoneIntegrationPanel() {
  const { t } = useTranslation('integration');
  const [form] = Form.useForm<IntegrationForm>();
  useLocalizedValidation(form);
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [testResult, setTestResult] = useState<AoneTestConnectionResult | null>(null);
  const [syncResult, setSyncResult] = useState<AoneSyncResult | null>(null);
  const [projects, setProjects] = useState<ExternalProject[]>([]);
  const [issueIdsText, setIssueIdsText] = useState('');

  const { data: bindings = [], isLoading: bindingsLoading } = useQuery({
    queryKey: ['aone-bindings'],
    queryFn: listAoneBindings,
  });

  const searchMutation = useMutation({
    mutationFn: async () => {
      const values = await credentialValues();
      return searchAoneProjects(values, form.getFieldValue('projectQuery') || '');
    },
    onSuccess: (result) => {
      setProjects(result.items || []);
      message.success(i18n.t('integration:projectsFound', { count: result.items?.length || 0 }));
    },
    onError: (error: Error) => { message.error(error.message || i18n.t('integration:searchFailed')); },
  });

  const testMutation = useMutation({
    mutationFn: testAoneConnection,
    onSuccess: (result) => {
      setTestResult(result);
      if (result.success) message.success(i18n.t('integration:connectionSucceeded'));
      else message.error(result.message || i18n.t('integration:connectionFailed'));
    },
    onError: (error: Error) => { message.error(error.message || i18n.t('integration:connectionFailed')); },
  });

  const createMutation = useMutation({
    mutationFn: async () => {
      const values = await readSaveValues();
      const selected = selectedProjects(values.selectedProjectIds || []);
      let reusedCount = 0;
      for (const project of selected) {
        const binding = await createAoneBinding({
          ...values,
          externalProjectId: project.externalId,
          externalProjectName: project.name || project.externalId,
        });
        if (binding.reusedExistingBinding) {
          reusedCount++;
        }
      }
      return { savedCount: selected.length, reusedCount };
    },
    onSuccess: ({ savedCount, reusedCount }) => {
      const createdCount = savedCount - reusedCount;
      if (reusedCount > 0) {
        message.success(i18n.t('integration:projectsReused', { count: savedCount, created: createdCount, reused: reusedCount }));
      } else {
        message.success(i18n.t('integration:projectsSaved', { count: savedCount }));
      }
      queryClient.invalidateQueries({ queryKey: ['aone-bindings'] });
    },
    onError: (error: Error) => { message.error(error.message || i18n.t('integration:saveFailed')); },
  });

  const syncMutation = useMutation({
    mutationFn: ({ bindingId, issueIds }: { bindingId: string; issueIds: string[] }) => syncAoneNow(bindingId, issueIds),
    onSuccess: (result) => {
      setSyncResult(result);
      message.success(i18n.t('integration:syncCompleted', { ...result, count: result.commentsImported }));
      queryClient.invalidateQueries({ queryKey: ['aone-bindings'] });
    },
    onError: (error: Error) => { message.error(error.message || i18n.t('integration:syncFailed')); },
  });

  const dispatchMutation = useMutation({
    mutationFn: dispatchAoneOutbox,
    onSuccess: (count) => { message.success(i18n.t('integration:outboxProcessed', { count })); },
    onError: (error: Error) => { message.error(error.message || i18n.t('integration:writebackFailed')); },
  });

  const projectOptions = useMemo(() => projects.map((project) => ({
    label: `${project.name || project.externalId} (${project.externalId})`,
    value: project.externalId,
  })), [projects]);

  const bindingColumns: ColumnsType<AoneBinding> = [
    { title: t('id'), dataIndex: 'id', width: 80 },
    { title: t('platform'), dataIndex: 'provider', width: 90, render: () => <Tag color="blue">{t('aone')}</Tag> },
    { title: t('managedProjects'), dataIndex: 'externalProjectName', align: 'left', render: (v, r) => v || r.externalProjectId },
    { title: t('projectId'), dataIndex: 'externalProjectId', width: 120 },
    { title: t('clientName'), dataIndex: 'clientKey', width: 150 },
    { title: t('writebackIdentity'), dataIndex: 'writebackStaffId', width: 180, render: (v) => v || '-' },
    {
      title: t('status'),
      dataIndex: 'enabled',
      width: 90,
      render: (v) => v ? <Tag color="success">{t('enabled')}</Tag> : <Tag>{t('disabled')}</Tag>,
    },
    {
      title: t('lastSync'),
      dataIndex: 'lastSuccessAt',
      width: 180,
      render: (_, record) => {
        const time = record.lastSuccessAt ? formatDateTime(record.lastSuccessAt, { dateStyle: 'medium', timeStyle: 'medium' }) : '-';
        return <Space direction="vertical" size={2}>
          <span>{time}</span>
          {record.lastError && (
            <Tooltip title={record.lastError}>
              <Tag color="error">{t('lastSyncFailed')}</Tag>
            </Tooltip>
          )}
        </Space>;
      },
    },
  ];

  async function credentialValues(): Promise<AoneBindingRequest> {
    const values = await form.validateFields(['provider', 'baseUrl', 'clientKey', 'accessSecret', 'regionId', 'writebackStaffId']);
    return {
      baseUrl: values.baseUrl,
      clientKey: values.clientKey,
      accessSecret: values.accessSecret,
      regionId: values.regionId || '1',
      writebackStaffId: values.writebackStaffId,
    };
  }

  async function readSaveValues(): Promise<IntegrationForm> {
    const values = await form.validateFields();
    return {
      ...values,
      baseUrl: values.baseUrl,
      regionId: values.regionId || '1',
      pollIntervalSeconds: values.pollIntervalSeconds || 3,
      enabled: true,
    };
  }

  function selectedProjects(ids: string[]) {
    return ids
      .map((id) => projects.find((project) => project.externalId === id) || { externalId: id, name: id, rawJson: '' })
      .filter((project) => project.externalId);
  }

  async function runConnectionTest() {
    const values = await readSaveValues();
    const selectedId = values.selectedProjectIds?.[0];
    if (!selectedId) {
      message.warning(i18n.t('integration:selectProjectFirst'));
      return;
    }
    const project = selectedProjects([selectedId])[0];
    testMutation.mutate({
      ...values,
      externalProjectId: project.externalId,
      externalProjectName: project.name || project.externalId,
    });
  }

  const selectedBindingId = bindings[0]?.id;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title={<Space size={8}>{t('platformConfig')}<Tooltip trigger={['hover', 'focus']} title={t('firstImportHelp')}>
          <Button type="text" size="small" aria-label={t('firstImport')} icon={<InfoCircleOutlined aria-hidden="true" />} />
        </Tooltip>
      </Space>} extra={<Space>
          <Button aria-label={t('testConnection')} icon={<ApiOutlined aria-hidden="true" />} loading={testMutation.isPending} onClick={() =>
            runAccessCommand('ADMIN', t('testCommand'), runConnectionTest)
          }>{t('testConnection')}</Button>
          <Button type="primary" aria-label={t('saveProjects')} icon={<SaveOutlined aria-hidden="true" />} loading={createMutation.isPending} onClick={() =>
            runAccessCommand('ADMIN', t('saveProjects'), () => createMutation.mutate())
          }>{t('saveProjects')}</Button>
        </Space>}>
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            provider: 'AONE',
            baseUrl: '',
            clientKey: 'auto-wonder',
            regionId: '1',
            pollIntervalSeconds: 3,
          }}
        >
          <div className="integration-fields">
            <Form.Item label={t('platform')} name="provider" rules={[{ required: true, message: t('requiredPlatform') }]}>
              <Select
                options={[
                  { label: t('aone'), value: 'AONE' },
                  { label: t('jiraPending'), value: 'JIRA', disabled: true },
                  { label: t('yunxiaoPending'), value: 'YUNXIAO', disabled: true },
                ]}
              />
            </Form.Item>
            <Form.Item label={t('baseUrl')} name="baseUrl" rules={[{ required: true }]} style={{ width: 300 }}>
              <Input />
            </Form.Item>
            <Form.Item label={t('clientKey')} name="clientKey" rules={[{ required: true, message: t('requiredClient') }]}>
              <Input />
            </Form.Item>
            <Form.Item label={t('accessSecret')} name="accessSecret" rules={[{ required: true, message: t('requiredAccessSecret') }]}>
              <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')}
                icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                style={{ padding: 0, width: 16, height: 16 }} />} />
            </Form.Item>

            <Form.Item label={t('regionId')} name="regionId">
              <Input />
            </Form.Item>
            <Form.Item
              label={t('writebackStaffId')}
              name="writebackStaffId"
              rules={[{ required: true, message: t('requiredStaffId') }]}
            >
              <Input />
            </Form.Item>
            {/* ponytail: upstream step buttons hardcode English aria names; keep keyboard steps until public label props exist. */}
            <Form.Item label={t('pollSeconds')} name="pollIntervalSeconds">
              <InputNumber controls={false} min={3} max={3600} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label={t('projectKeyword')} htmlFor="projectQuery">
              <Space.Compact style={{ width: '100%' }}>
                <Form.Item name="projectQuery" noStyle>
                  <Input placeholder={t('projectQuery')} />
                </Form.Item>
                <Button icon={<SearchOutlined aria-hidden="true" />} aria-label={t('searchProjects')} loading={searchMutation.isPending} onClick={() =>
                  runAccessCommand('ADMIN', t('searchProjects'), () => searchMutation.mutate())
                }>{t('search')}</Button>
              </Space.Compact>
            </Form.Item>
          </div>
          <Form.Item
            label={t('managedProjects')}
            name="selectedProjectIds"
            rules={[{ required: true, message: t('requiredProjects') }]}
            style={{ marginBottom: 0 }}
          >
            <Select mode="multiple" options={projectOptions} placeholder={t('projectPlaceholder')} />
          </Form.Item>
        </Form>
        {testResult && (
          <Alert
            style={{ marginTop: 16 }}
            type={testResult.success ? 'success' : 'error'}
            message={testResult.success ? t('connectionSucceeded') : testResult.message || t('connectionFailed')}
            description={testResult.checks?.join(' / ') || null}
          />
        )}
      </Card>

      <Card title={t('manualSync')}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div className="integration-sync-toolbar">
            <Input
              value={issueIdsText}
              aria-label={t('externalWorkitemIds')}
              placeholder={t('issueIdsHint')}
              onChange={(event) => setIssueIdsText(event.target.value)}
            />
            <Space>
              <Button
                aria-label={t('syncIds')}
                icon={<CloudSyncOutlined aria-hidden="true" />}
                disabled={!selectedBindingId}
                loading={syncMutation.isPending}
                onClick={() => {
                  runAccessCommand('ADMIN', t('syncCommand'), () => {
                    const issueIds = issueIdsText
                      .split(',')
                      .map((v: string) => v.trim())
                      .filter(Boolean);
                    syncMutation.mutate({ bindingId: selectedBindingId, issueIds });
                  });
                }}
              >{t('syncIds')}</Button>
              <Button aria-label={t('outbox')} icon={<SendOutlined aria-hidden="true" />} loading={dispatchMutation.isPending} onClick={() =>
                runAccessCommand('ADMIN', t('outboxCommand'), () => dispatchMutation.mutate())
              }>{t('outbox')}</Button>
            </Space>
          </div>
          {syncResult && (
            <Alert
              type="success"
              message={t('syncResults', { ...syncResult, count: syncResult.commentsImported })}
              description={<Text>{t('localIds')}{syncResult.workitemIds.join(', ') || '-'}</Text>}
            />
          )}
        </Space>
      </Card>

      <Card title={t('existingProjects')}>
        <Table scroll={{ x: 1100 }} rowKey="id" columns={bindingColumns} dataSource={bindings} loading={bindingsLoading} pagination={false} />
      </Card>
    </Space>
  );
}
