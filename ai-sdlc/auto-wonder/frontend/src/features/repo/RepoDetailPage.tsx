import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { useState } from 'react';
import {
  Card, Descriptions, Button, Spin, Result, message, Tabs, Form, Input, Space,
  Tag, Modal, Select, Popconfirm, Drawer} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { ScanOutlined, EditOutlined, SaveOutlined, DeleteOutlined, PlusOutlined, CloseCircleFilled } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getRepo, getConclusion, updateConclusion, listRelations, listRepos,
  createRelation, deleteRelation, deleteRepo, updateRepo, RELATION_TYPES,
} from './api';
import type { RepoRelation, CreateRelationRequest, UpdateRepoRequest } from './api';
import type { ColumnsType } from 'antd/es/table';
import { isValidRepoName, repoNameInvalidMessage } from './repoNameValidation';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;

// 临时隐藏仓库扫描入口(发起扫描按钮/扫描结论页签/扫描状态字段),置回 true 即可恢复
const REPO_SCAN_ENABLED: boolean = false;

export function RepoDetailPage() {
  const { t } = useTranslation('repo');
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const repoId = id ?? '';
  const hasValidRepoId = /^\d+$/.test(repoId);

  const [editingRepo, setEditingRepo] = useState(false);
  const [editingConclusion, setEditingConclusion] = useState(false);
  const [addRelationOpen, setAddRelationOpen] = useState(false);
  const [aiScanOpen, setAiScanOpen] = useState(false);
  const [deleteRepoOpen, setDeleteRepoOpen] = useState(false);
  const [pendingRelationDeleteId, setPendingRelationDeleteId] = useState<string | number | null>(null);
  const [repoForm] = Form.useForm();
  const [conclusionForm] = Form.useForm();
  const [relationForm] = Form.useForm();

  const { data: repo, isLoading, isError, error } = useQuery({
    queryKey: ['repo', repoId],
    queryFn: () => getRepo(repoId),
    enabled: hasValidRepoId,
  });

  const { data: conclusion } = useQuery({
    queryKey: ['repo-conclusion', repoId],
    queryFn: () => getConclusion(repoId),
    enabled: hasValidRepoId,
  });

  const { data: repoRelations = [] } = useQuery({
    queryKey: ['repo-relations', repoId],
    queryFn: () => listRelations(repoId),
    enabled: hasValidRepoId,
  });

  const { data: allRepos = [] } = useQuery({
    queryKey: ['repos', 1, 100],
    queryFn: () => listRepos({ page: 1, size: 100 }),
  });

  const updateRepoMut = useMutation({
    mutationFn: (data: UpdateRepoRequest) => updateRepo(repoId, data),
    onSuccess: () => {
      message.success(i18n.t('repo:feedback.repoSaved'));
      setEditingRepo(false);
      queryClient.invalidateQueries({ queryKey: ['repo', repoId] });
      queryClient.invalidateQueries({ queryKey: ['repos'] });
    },
    onError: (e: Error) => message.error(e.message || i18n.t('repo:errors.save')),
  });

  const updateConclusionMut = useMutation({
    mutationFn: (data: Parameters<typeof updateConclusion>[1]) => updateConclusion(repoId, data),
    onSuccess: () => {
      message.success(i18n.t('repo:feedback.conclusionSaved'));
      setEditingConclusion(false);
      queryClient.invalidateQueries({ queryKey: ['repo-conclusion', repoId] });
    },
    onError: (e: Error) => message.error(e.message || i18n.t('repo:errors.save')),
  });

  const createRelationMut = useMutation({
    mutationFn: (data: CreateRelationRequest) => createRelation(data),
    onSuccess: () => {
      message.success(i18n.t('repo:feedback.relationCreated'));
      setAddRelationOpen(false);
      relationForm.resetFields();
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
    onError: (e: Error) => message.error(e.message || i18n.t('repo:errors.create')),
  });

  const deleteRelationMut = useMutation({
    mutationFn: deleteRelation,
    onSuccess: () => {
      message.success(i18n.t('repo:feedback.relationDeleted'));
      setPendingRelationDeleteId(null);
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
  });

  const deleteRepoMut = useMutation({
    mutationFn: () => deleteRepo(repoId),
    onSuccess: () => {
      message.success(i18n.t('repo:feedback.deleted'));
      setDeleteRepoOpen(false);
      queryClient.invalidateQueries({ queryKey: ['repos'] });
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
      navigate('/repos');
    },
    onError: (e: Error) => {
      setDeleteRepoOpen(false);
      message.error(e.message || i18n.t('repo:errors.deleteRepo'));
    },
  });

  const formReady = hasValidRepoId && !isLoading && !isError && Boolean(repo);
  useLocalizedValidation(repoForm, formReady && editingRepo);
  useLocalizedValidation(conclusionForm, REPO_SCAN_ENABLED && formReady && editingConclusion);
  useLocalizedValidation(relationForm, formReady && addRelationOpen);

  if (!hasValidRepoId) return (
    <Result status="404" title={t('errors.invalidId')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title={t('errors.load')} subTitle={error?.message || t('errors.retry')}
      extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (!repo) return null;

  const repoNameMap = new Map(allRepos.map(r => [String(r.id), r.name]));

  const handleSaveConclusion = async () => {
    await runWithAccess('READ_WRITE', t('actions.saveConclusion'), async () => {
      const values = await conclusionForm.validateFields();
      updateConclusionMut.mutate(values);
    });
  };

  const handleStartRepoEdit = () => {
    runWithAccess('READ_WRITE', t('actions.editRepo'), () => {
      repoForm.setFieldsValue({
        name: repo?.name || '',
        url: repo?.url || '',
        defaultBranch: repo?.defaultBranch || '',
        description: repo?.description || '',
      });
      setEditingRepo(true);
    });
  };

  const handleSaveRepo = async () => {
    await runWithAccess('READ_WRITE', t('actions.saveRepo'), async () => {
      // 校验失败时 antd 已在表单项下方展示错误信息，这里吞掉拒绝避免未处理 Promise。
      const values = await repoForm.validateFields().catch(() => null);
      if (!values) {
        return;
      }
      updateRepoMut.mutate({
        name: values.name.trim(),
        url: values.url.trim(),
        defaultBranch: values.defaultBranch?.trim() ? values.defaultBranch.trim() : null,
        description: values.description?.trim() ? values.description.trim() : null,
      });
    });
  };

  const handleStartEdit = () => {
    runWithAccess('READ_WRITE', t('actions.editConclusion'), () => {
      conclusionForm.setFieldsValue({
        purpose: conclusion?.purpose || '',
        keyBusiness: conclusion?.keyBusiness || '',
        upstreams: conclusion?.upstreams || '',
        downstreams: conclusion?.downstreams || '',
        summaryMd: conclusion?.summaryMd || '',
      });
      setEditingConclusion(true);
    });
  };

  const handleAddRelation = async () => {
    await runWithAccess('READ_WRITE', t('actions.addRepoRelation'), async () => {
      const values = await relationForm.validateFields().catch(() => null);
      if (values) createRelationMut.mutate(values);
    });
  };

  const openAiScan = () => {
    runWithAccess('READ_WRITE', t('actions.startScan'), () => setAiScanOpen(true));
  };

  const relationColumns: ColumnsType<RepoRelation> = [
    {
      title: t('fields.peer'), key: 'peer', ellipsis: { showTitle: false },
      render: (_: unknown, r: RepoRelation) => {
        const fromRepoId = String(r.fromRepoId);
        const toRepoId = String(r.toRepoId);
        const peerId = fromRepoId === repoId ? toRepoId : fromRepoId;
        const direction = fromRepoId === repoId ? '→' : '←';
        const peerName = repoNameMap.get(peerId) || `#${peerId}`;
        return <EllipsisText tooltip={peerName}>{direction} {peerName}</EllipsisText>;
      },
    },
    {
      // 不设固定 width：关系类型标签长短不一（如「客户端调用服务端」），固定 100px 会把长标签挤成省略号
      title: t('fields.relationType'), dataIndex: 'relationType', width: 140, ellipsis: { showTitle: false },
      render: (t: string) => (
        <EllipsisText tooltip={RELATION_TYPES.find(rt => rt.value === t)?.label || t}>
          <Tag style={{ marginInlineEnd: 0 }}>{RELATION_TYPES.find(rt => rt.value === t)?.label || t}</Tag>
        </EllipsisText>
      ),
    },
    {
      title: t('fields.description'), dataIndex: 'description', align: 'left', ellipsis: { showTitle: false },
      render: (v: string | null) => (v ? <EllipsisText>{v}</EllipsisText> : '-'),
    },
    {
      title: t('fields.actions'), width: 80,
      render: (_: unknown, r: RepoRelation) => (
        <Popconfirm
          title={t('confirm.deleteRelation')}
          open={pendingRelationDeleteId === r.id}
          onOpenChange={(open) => {
            if (!open) setPendingRelationDeleteId(null);
          }}
          onConfirm={() => runWithAccess(
            'READ_WRITE',
            t('actions.deleteRepoRelation'),
            () => deleteRelationMut.mutate(r.id),
          )}
        >
          <Button
            type="link"
            size="small"
            danger
            aria-label={t('actions.deleteRepoRelation')}
            icon={<DeleteOutlined aria-hidden="true" />}
            onClick={() => runWithAccess(
              'READ_WRITE',
              t('actions.deleteRepoRelation'),
              () => setPendingRelationDeleteId(r.id),
            )}
          />
        </Popconfirm>
      ),
    },
  ];

  const tabItems = [
    {
      key: 'info',
      label: t('tabs.info'),
      children: editingRepo ? (
        <Form form={repoForm} layout="vertical">
          <Form.Item label={t('fields.repoName')} name="name"
            rules={[
              { required: true, message: t('validation.nameRequired') },
              {
                validator: (_, value) => (
                  isValidRepoName(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error(repoNameInvalidMessage()))
                ),
              },
            ]}>
            <Input placeholder={t('fields.repoName')} />
          </Form.Item>
          <Form.Item label={t('fields.url')} name="url"
            rules={[{ required: true, message: t('validation.urlRequired') }]}>
            <Input placeholder={t('placeholders.repoUrl')} />
          </Form.Item>
          <Form.Item label={t('fields.branch')} name="defaultBranch">
            <Input placeholder={t('placeholders.clearBranch')} allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('actions.clearBranch')} /> }} />
          </Form.Item>
          <Form.Item label={t('fields.description')} name="description">
            <TextArea rows={3} placeholder={t('placeholders.clearDescription')} allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('actions.clearDescription')} /> }} />
          </Form.Item>
          <Space>
            <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} onClick={handleSaveRepo}
              loading={updateRepoMut.isPending}>{t('actions.save')}</Button>
            <Button onClick={() => setEditingRepo(false)}>{t('actions.cancel')}</Button>
          </Space>
        </Form>
      ) : (
        <>
          <Descriptions column={2}>
            <Descriptions.Item label={t('fields.repoName')}>{repo.name}</Descriptions.Item>
            <Descriptions.Item label={t('fields.url')} span={2}>{repo.url}</Descriptions.Item>
            <Descriptions.Item label={t('fields.branch')}>{repo.defaultBranch || '-'}</Descriptions.Item>
            {REPO_SCAN_ENABLED && (
              <Descriptions.Item label={t('scan.status')}>
                {conclusion
                  ? <Tag color="success">{t('scan.complete')}</Tag>
                  : <Tag>{t('scan.notScanned')}</Tag>}
              </Descriptions.Item>
            )}
            {REPO_SCAN_ENABLED && conclusion && (
              <Descriptions.Item label={t('scan.lastTime')}>
                {formatDateTime(conclusion.gmtCreate)}
              </Descriptions.Item>
            )}
            <Descriptions.Item label={t('fields.description')} span={2}>{repo.description || '-'}</Descriptions.Item>
          </Descriptions>
          <Button icon={<EditOutlined aria-hidden="true" />} onClick={handleStartRepoEdit} style={{ marginTop: 16 }}>
            {t('actions.edit')}
          </Button>
        </>
      ),
    },
    ...(REPO_SCAN_ENABLED ? [{
      key: 'conclusion',
      label: t('tabs.conclusion'),
      children: editingConclusion ? (
        <Form form={conclusionForm} layout="vertical">
          <Form.Item label={t('scan.purpose')} name="purpose">
            <Input placeholder={t('scan.purposePlaceholder')} />
          </Form.Item>
          <Form.Item label={t('scan.business')} name="keyBusiness">
            <Input placeholder={t('scan.businessPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('scan.upstreams')} name="upstreams">
            <TextArea rows={2} placeholder={t('scan.upstreamsPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('scan.downstreams')} name="downstreams">
            <TextArea rows={2} placeholder={t('scan.downstreamsPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('scan.markdown')} name="summaryMd">
            <TextArea rows={8} placeholder={t('scan.summaryPlaceholder')} />
          </Form.Item>
          <Space>
            <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} onClick={handleSaveConclusion}
              loading={updateConclusionMut.isPending}>{t('actions.save')}</Button>
            <Button onClick={() => setEditingConclusion(false)}>{t('actions.cancel')}</Button>
          </Space>
        </Form>
      ) : (
        <div>
          {conclusion ? (
            <>
              <Descriptions column={2} style={{ marginBottom: 16 }}>
                <Descriptions.Item label={t('scan.purpose')}>{conclusion.purpose || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('scan.business')}>{conclusion.keyBusiness || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('scan.upstreams')}>{conclusion.upstreams || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('scan.downstreams')}>{conclusion.downstreams || '-'}</Descriptions.Item>
              </Descriptions>
              {conclusion.summaryMd && (
                <Card size="small" title={t('scan.summary')}>
                  <MarkdownView content={conclusion.summaryMd} />
                </Card>
              )}
            </>
          ) : (
            <Result status="info" title={t('scan.empty')} subTitle={t('scan.emptyHint')} />
          )}
          <Button icon={<EditOutlined aria-hidden="true" />} onClick={handleStartEdit} style={{ marginTop: 16 }}>
            {t('actions.editConclusionButton')}
          </Button>
        </div>
      ),
    }] : []),
    {
      key: 'relations',
      label: t('tabs.relations'),
      children: (
        <div>
          <div style={{ marginBottom: 16, textAlign: 'right' }}>
            <Button
              icon={<PlusOutlined aria-hidden="true" />}
              onClick={() => runWithAccess(
                'READ_WRITE',
                t('actions.addRepoRelation'),
                () => setAddRelationOpen(true),
              )}
            >
              {t('actions.addRelation')}
            </Button>
          </div>
          <Table rowKey="id" columns={relationColumns} dataSource={repoRelations} pagination={false} size="small" />
        </div>
      ),
    },
  ];

  return (
    <div>

      <Card
        className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/repos" label={t('actions.backList')} /><span>{repo.name}</span></span>} />}
        extra={
          <Space>
            {REPO_SCAN_ENABLED && (
              <Button icon={<ScanOutlined aria-hidden="true" />} onClick={openAiScan}>
                {t('actions.scan')}
              </Button>
            )}
            <Popconfirm
              title={t('confirm.deleteRepo', { name: repo.name })}
              description={t('confirm.deleteHint')}
              open={deleteRepoOpen}
              onOpenChange={(open) => setDeleteRepoOpen(open)}
              onConfirm={() => runWithAccess(
                'READ_WRITE',
                t('actions.deleteRepo'),
                () => deleteRepoMut.mutate(),
              )}
              okText={t('actions.delete')}
              okButtonProps={{ danger: true, loading: deleteRepoMut.isPending }}
              cancelText={t('actions.cancel')}
            >
              <Button
                danger
                icon={<DeleteOutlined aria-hidden="true" />}
                onClick={() => runWithAccess(
                  'READ_WRITE',
                  t('actions.deleteRepo'),
                  () => setDeleteRepoOpen(true),
                )}
              >
                {t('actions.deleteRepo')}
              </Button>
            </Popconfirm>
          </Space>
        }
      >
        <Tabs items={tabItems} />
      </Card>

      <Modal title={t('actions.addRepoRelation')} open={addRelationOpen}
        onOk={handleAddRelation} onCancel={() => setAddRelationOpen(false)}
        confirmLoading={createRelationMut.isPending}>
        <Form form={relationForm} layout="vertical">
          <Form.Item label={t('fields.source')} name="fromRepoId" initialValue={repoId} rules={[{ required: true }]}>
            <Select showSearch optionFilterProp="label"
              options={allRepos.map(r => ({ value: r.id, label: r.name }))}
            />
          </Form.Item>
          <Form.Item label={t('fields.target')} name="toRepoId" rules={[{ required: true, message: t('validation.targetRequired') }]}>
            <Select placeholder={t('placeholders.target')} showSearch optionFilterProp="label"
              options={allRepos.filter(r => String(r.id) !== repoId).map(r => ({ value: r.id, label: r.name }))}
            />
          </Form.Item>
          <Form.Item label={t('fields.relationType')} name="relationType" rules={[{ required: true, message: t('validation.typeRequired') }]}>
            <Select placeholder={t('placeholders.type')} options={RELATION_TYPES.map(t => ({ value: t.value, label: t.label }))} />
          </Form.Item>
          <Form.Item label={t('fields.description')} name="description">
            <Input placeholder={t('placeholders.description')} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={t('scan.drawer')}
        width={880}
        open={aiScanOpen}
        onClose={() => setAiScanOpen(false)}
        destroyOnClose
      >
        <AiSessionPanel
          scene="REPO_SCAN"
          bizRefType="REPO"
          bizRefId={repoId}
          autoStartInput={`请扫描仓库 ${repo.name}，仓库地址：${repo.url}，默认分支：${repo.defaultBranch || '未配置'}。请分析其作用、关键业务、上下游关系，并输出结构化 JSON。`}
          onConfirm={() => {
            queryClient.invalidateQueries({ queryKey: ['repo', repoId] });
            queryClient.invalidateQueries({ queryKey: ['repo-conclusion', repoId] });
            setAiScanOpen(false);
            message.success(i18n.t('repo:feedback.scanConfirmed'));
          }}
        />
      </Drawer>
    </div>
  );
}
