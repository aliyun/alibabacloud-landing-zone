import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useState, useEffect } from 'react';
import { Card, Tabs, Spin, Empty, Button } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { WorkType } from './types';
import { TemplateSelector } from './components/TemplateSelector';
import { StatusNodeEditor } from './components/StatusNodeEditor';
import { TransitionEditor } from './components/TransitionEditor';
import {
  useTemplates, useTemplateDetail,
  useCreateTemplate, useUpdateTemplate, useDeleteTemplate,
  useCreateNode, useUpdateNode, useDeleteNode,
  useCreateTransition, useUpdateTransition, useDeleteTransition,
} from './hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

export function StatusTemplatePage() {
  const { t } = useTranslation('statemachine');
  const workTypes: { key: WorkType; label: string }[] = [
    { key: 'REQ', label: t('workTypes.REQ') },
    { key: 'TASK', label: t('workTypes.TASK') },
    { key: 'BUG', label: t('workTypes.BUG') },
  ];
  const accessCommand = useAccessCommand();
  const [createOpen, setCreateOpen] = useState(false);
  const [activeType, setActiveType] = useState<WorkType>('REQ');
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const { data: templates = [], isLoading: templatesLoading } = useTemplates(activeType);
  const { data: detail, isLoading: detailLoading } = useTemplateDetail(selectedId);

  const createTemplateMut = useCreateTemplate(activeType);
  const updateTemplateMut = useUpdateTemplate(activeType);
  const deleteTemplateMut = useDeleteTemplate(activeType);

  useEffect(() => {
    if (templates.length > 0 && (!selectedId || !templates.find((t) => t.id === selectedId))) {
      setSelectedId(templates[0].id);
    }
  }, [templates, selectedId]);

  useEffect(() => { setSelectedId(null); }, [activeType]);

  const createNodeMut = useCreateNode(selectedId || 0);
  const updateNodeMut = useUpdateNode(selectedId || 0);
  const deleteNodeMut = useDeleteNode(selectedId || 0);
  const createTransitionMut = useCreateTransition(selectedId || 0);
  const updateTransitionMut = useUpdateTransition(selectedId || 0);
  const deleteTransitionMut = useDeleteTransition(selectedId || 0);

  return (
    <Card className="aw-content-card" title={<PageHeading title={t('page.title')} />}
      extra={<Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => accessCommand(
        'READ_WRITE', t('actions.createTemplate'), () => setCreateOpen(true),
      )}>{t('page.create')}</Button>}
    >
      <Tabs
        activeKey={activeType}
        onChange={(k) => setActiveType(k as WorkType)}
        items={workTypes.map((wt) => ({ key: wt.key, label: wt.label }))}
      />

      {templatesLoading ? <Spin /> : (
        <>
          <TemplateSelector
            createOpen={createOpen}
            onCloseCreate={() => setCreateOpen(false)}
            templates={templates}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onCreate={(name) => accessCommand(
              'READ_WRITE',
              t('actions.createTemplate'),
              () => createTemplateMut.mutate({ workType: activeType, name }),
            )}
            onSetDefault={(id) => accessCommand(
              'READ_WRITE',
              t('actions.defaultTemplate'),
              () => updateTemplateMut.mutate({ id, data: { isDefault: true } }),
            )}
            onDelete={(id) => accessCommand('READ_WRITE', t('actions.deleteTemplate'), () => {
              deleteTemplateMut.mutate(id);
              setSelectedId(null);
            })}
            workType={activeType}
          />

          {detailLoading ? <Spin style={{ marginTop: 24 }} /> : detail ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 24, marginTop: 16 }}>
              <StatusNodeEditor
                nodes={detail.nodes}
                onCreate={(data) => accessCommand(
                  'READ_WRITE',
                  t('actions.createNode'),
                  () => createNodeMut.mutate(data),
                )}
                onUpdate={(nodeId, data) => accessCommand(
                  'READ_WRITE',
                  t('actions.editNode'),
                  () => updateNodeMut.mutate({ nodeId, data }),
                )}
                onDelete={(nodeId) => accessCommand(
                  'READ_WRITE',
                  t('actions.deleteNode'),
                  () => deleteNodeMut.mutate(nodeId),
                )}
                createLoading={createNodeMut.isPending}
                updateLoading={updateNodeMut.isPending}
              />
              <TransitionEditor
                transitions={detail.transitions}
                nodes={detail.nodes}
                onCreate={(data) => accessCommand(
                  'READ_WRITE',
                  t('actions.createTransition'),
                  () => createTransitionMut.mutate(data),
                )}
                onUpdate={(tid, data) => accessCommand(
                  'READ_WRITE',
                  t('actions.editTransition'),
                  () => updateTransitionMut.mutate({ tid, data }),
                )}
                onDelete={(tid) => accessCommand(
                  'READ_WRITE',
                  t('actions.deleteTransition'),
                  () => deleteTransitionMut.mutate(tid),
                )}
                createLoading={createTransitionMut.isPending}
                updateLoading={updateTransitionMut.isPending}
              />
            </div>
          ) : <Empty description={t('page.empty')} style={{ marginTop: 24 }} />}
        </>
      )}
    </Card>
  );
}
