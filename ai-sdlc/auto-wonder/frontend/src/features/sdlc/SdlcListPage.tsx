import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { formatNumber } from '@/shared/i18n/format';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useMemo, useState } from 'react';
import { Card, Tag, Button, Space, Popconfirm, message, Modal, Form, Input, Select, Tabs, Pagination, Spin, Empty } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listSdlcTemplatesPage, createSdlcTemplate, deleteSdlcTemplate, enableSdlcTemplate, disableSdlcTemplate } from './api';
import type { SdlcTemplate } from './api';
import type { ColumnsType } from 'antd/es/table';
import { SquadTemplateGallery } from './SquadTemplateGallery';
import { SquadWorkflowPanel } from './workflow/SquadWorkflowPanel';
import { SDLC_AI_ENABLED } from './featureFlags';
import { SquadFilterBar, useSquadOptions } from '@/features/squad/SquadFilterBar';
import { SquadTags } from '@/features/squad/SquadTags';
import { groupBySquad } from '@/features/squad/squadGrouping';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { EllipsisText } from '@/shared/ui/EllipsisText';

const SDLCS_VIEW_STORAGE_KEY = 'autowonder.sdlcs.view';
const SDLCS_VIEW_OPTIONS = ['list', 'grouped'] as const;
const SDLCS_PAGE_SIZE_STORAGE_KEY = 'autowonder.sdlcs.pageSize';

const statusMap: Record<string, { color: string; key: 'status.draft' | 'status.enabled' | 'status.disabled' }> = {
  DRAFT: { color: 'default', key: 'status.draft' as const },
  ENABLED: { color: 'success', key: 'status.enabled' as const },
  DISABLED: { color: 'warning', key: 'status.disabled' as const },
};

export function SdlcListPage() {
  const { t } = useTranslation('sdlc');
  const { t: squadT } = useTranslation('squad');
  const workTypes = new Map<string, string>([['REQUIREMENT', t('workType.requirement')], ['REQ', t('workType.requirement')], ['TASK', t('workType.task')], ['BUG', t('workType.bug')]]);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const rawAccessCommand = useAccessCommand();
  const accessCommand: typeof rawAccessCommand = (required, action, command) =>
    rawAccessCommand(required, action, command, 'SDLC');
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference(SDLCS_PAGE_SIZE_STORAGE_KEY, [10, 20, 50, 100], 10);
  const [createOpen, setCreateOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('templates');
  const [squadFilter, setSquadFilter] = useState<number[]>([]);
  const [grouped, setGrouped] = useState(
    () => readViewPreference(SDLCS_VIEW_STORAGE_KEY, SDLCS_VIEW_OPTIONS, 'grouped') === 'grouped',
  );
  const [form] = Form.useForm();
  useLocalizedValidation(form, createOpen);
  const { options: squadOptions, nameById: squadNameById, isLoading: squadsLoading } = useSquadOptions();

  const { data: result, isLoading } = useQuery({
    queryKey: ['sdlcs', 'page', page, size, squadFilter],
    queryFn: () => listSdlcTemplatesPage({ page, size, squadIds: squadFilter }),
  });

  const data = result?.list ?? [];
  const total = result?.total ?? 0;

  // The squad filter is applied server-side, so page 1 is the only page that can hold the new result set.
  useEffect(() => {
    setPage(1);
  }, [squadFilter]);

  // Deleting the last row (or a concurrent change) can invalidate the current page.
  useEffect(() => {
    if (result) {
      const lastPage = Math.max(1, Math.ceil(result.total / size));
      if (page > lastPage) setPage(lastPage);
    }
  }, [result, page, size]);

  const squadGroups = useMemo(
    () => groupBySquad(data, (sdlc) => sdlc.squadIds, squadNameById),
    [data, squadNameById, squadT],
  );

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['sdlcs'] });

  const createMut = useMutation({
    mutationFn: createSdlcTemplate,
    onSuccess: (newSdlc) => {
      invalidate();
      setCreateOpen(false);
      form.resetFields();
      message.success(i18n.t('sdlc:messages.created'));
      navigate(`/sdlcs/${newSdlc.id}`);
    },
  });

  const deleteMut = useMutation({
    mutationFn: deleteSdlcTemplate,
    onSuccess: () => { invalidate(); message.success(i18n.t('sdlc:messages.deleted')); },
    onError: (error: Error) => { message.error(error.message || i18n.t('sdlc:messages.deleteFailed')); },
  });

  const enableMut = useMutation({
    mutationFn: enableSdlcTemplate,
    onSuccess: () => { invalidate(); message.success(i18n.t('sdlc:status.enabled')); },
  });

  const disableMut = useMutation({
    mutationFn: disableSdlcTemplate,
    onSuccess: () => { invalidate(); message.success(i18n.t('sdlc:status.disabled')); },
  });

  const columns: ColumnsType<SdlcTemplate> = [
    { title: t('fields.id'), dataIndex: 'id', width: 70 },
    {
      title: t('fields.name'), dataIndex: 'name', align: 'left', ellipsis: { showTitle: false },
      render: (name: string, record) => (
        <EllipsisText tooltip={name}>
          <a onClick={() => navigate(`/sdlcs/${record.id}`)}>{name}</a>
        </EllipsisText>
      ),
    },
    { title: t('fields.description'), dataIndex: 'description', align: 'left', ellipsis: { showTitle: false }, render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText> },
    { title: t('fields.workType'), dataIndex: 'workType', width: 90, render: (v: string | null) => v ? workTypes.get(v) ?? v : t('workType.general') },
    { title: t('fields.stepCount'), width: 80, render: (_, record) => formatNumber(record.stepCount ?? record.steps?.length ?? 0) },
    {
      title: t('fields.squad'), width: 180,
      render: (_, record) => <SquadTags squadIds={record.squadIds} squadNames={record.squadNames} />,
    },
    {
      title: t('fields.status'), dataIndex: 'status', width: 100,
      render: (s: string) => <Tag color={Object.prototype.hasOwnProperty.call(statusMap, s) ? statusMap[s].color : undefined}>{Object.prototype.hasOwnProperty.call(statusMap, s) ? t(statusMap[s].key) : s}</Tag>,
    },
    {
      title: t('fields.actions'), width: 180,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => navigate(`/sdlcs/${record.id}`)}>{t('actions.details')}</Button>
          {record.status === 'DRAFT' || record.status === 'DISABLED' ? (
            <Popconfirm title={t('confirm.enable')} onConfirm={() => accessCommand(
              'READ_WRITE',
              t('commands.enable'),
              () => enableMut.mutate(record.id),
            )}>
              <Button type="link" size="small">{t('actions.enable')}</Button>
            </Popconfirm>
          ) : (
            <Popconfirm title={t('confirm.disable')} onConfirm={() => accessCommand(
              'READ_WRITE',
              t('commands.disable'),
              () => disableMut.mutate(record.id),
            )}>
              <Button type="link" size="small" danger>{t('actions.disable')}</Button>
            </Popconfirm>
          )}
          {record.status !== 'ENABLED' && (
            <Popconfirm title={t('confirm.delete')} onConfirm={() => accessCommand(
              'READ_WRITE',
              t('commands.delete'),
              () => deleteMut.mutate(record.id),
            )}>
              <Button type="link" size="small" danger aria-label={t('commands.delete')} icon={<DeleteOutlined aria-hidden="true" />} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <PageHeading
        title={t('list.title')}
        extra={activeTab === 'templates' && (
          <Space>
            {SDLC_AI_ENABLED && (
              <Button onClick={() => accessCommand(
                'READ_WRITE',
                t('commands.aiGenerate'),
                () => navigate('/sdlcs/generate'),
              )}>{t('actions.aiGenerate')}</Button>
            )}
            <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => accessCommand(
              'READ_WRITE',
              t('commands.create'),
              () => setCreateOpen(true),
            )}>{t('actions.new')}</Button>
          </Space>
        )}
      />
    <Tabs
      activeKey={activeTab}
      onChange={setActiveTab}
      items={[
        {
          key: 'templates',
          label: t('list.templates'),
          children: (
            <>
              <div style={{ marginBottom: 12 }}>
                <SquadFilterBar
                  options={squadOptions}
                  value={squadFilter}
                  onChange={setSquadFilter}
                  grouped={grouped}
                  onGroupedChange={(next) => {
                    setGrouped(next);
                    writeViewPreference(SDLCS_VIEW_STORAGE_KEY, next ? 'grouped' : 'list');
                  }}
                  loading={squadsLoading}
                />
              </div>
              <Card className="aw-content-card">
                {grouped ? (
                  <Spin spinning={isLoading}>
                    {!isLoading && data.length === 0 ? (
                      <Empty description={t('list.empty')} />
                    ) : (
                      <>
                        {squadGroups.map((group) => (
                          <section key={group.key} className="sdlc-squad-group" style={{ marginBottom: 20 }}>
                            <Space className="sdlc-squad-group-header" style={{ marginBottom: 8 }}>
                              <strong>
                                {group.squadId == null ? (
                                  group.label
                                ) : (
                                  <Link to={`/agents?tab=squads&squadId=${group.squadId}`}>{group.label}</Link>
                                )}
                              </strong>
                              <span style={{ color: 'var(--aw-muted)', fontSize: 12 }}>{t('list.groupCount', { count: group.items.length })}</span>
                            </Space>
                            <Table
                              rowKey="id"
                              columns={columns}
                              dataSource={group.items}
                              loading={isLoading}
                              pagination={false}
                            />
                          </section>
                        ))}
                      </>
                    )}
                    <Pagination
                      current={page}
                      pageSize={size}
                      total={total}
                      showSizeChanger
                      showTotal={(count) => t('list.total', { count })}
                      onChange={(p, ps) => { setPage(ps !== size ? 1 : p); setSize(ps); }}
                      style={{ marginTop: 8, textAlign: 'right' }}
                    />
                  </Spin>
                ) : (
                  <Table
                    rowKey="id"
                    columns={columns}
                    dataSource={data}
                    loading={isLoading}
                    pagination={{
                      current: page, pageSize: size, total,
                      onChange: (p, ps) => { setPage(ps !== size ? 1 : p); setSize(ps); },
                      showSizeChanger: true,
                      showTotal: (count) => t('list.total', { count }),
                    }}
                  />
                )}
              </Card>

              <Modal
                title={t('commands.create')}
                open={createOpen}
                closable={{ 'aria-label': t('gallery.close') }}
                onOk={() => form.validateFields().then(v => accessCommand(
                  'READ_WRITE',
                  t('commands.create'),
                  () => createMut.mutate(v),
                ), () => {})}
                onCancel={() => setCreateOpen(false)}
                confirmLoading={createMut.isPending}
              >
                <Form form={form} layout="vertical">
                  <Form.Item name="name" label={t('fields.templateName')} rules={[{ required: true, message: t('validation.name') }]}>
                    <Input placeholder={t('placeholder.name')} />
                  </Form.Item>
                  <Form.Item name="description" label={t('fields.description')}>
                    <Input.TextArea rows={2} placeholder={t('placeholder.description')} />
                  </Form.Item>
                  <Form.Item name="workType" label={t('fields.workType')}>
                    <Select allowClear placeholder={t('placeholder.workType')}
                      options={[
                        { value: 'REQUIREMENT', label: t('workType.requirement') },
                        { value: 'TASK', label: t('workType.task') },
                        { value: 'BUG', label: t('workType.bug') },
                      ]} />
                  </Form.Item>
                </Form>
              </Modal>
            </>
          ),
        },
        {
          key: 'gallery',
          label: t('list.gallery'),
          children: <SquadTemplateGallery />,
        },
        {
          key: 'workflow',
          label: t('workflow.tab'),
          children: <SquadWorkflowPanel />,
        },
      ]}
    />
    </div>
  );
}
