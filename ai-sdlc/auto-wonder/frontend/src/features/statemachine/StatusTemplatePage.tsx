import { useState, useEffect } from 'react';
import { Card, Tabs, Spin, Empty } from 'antd';
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

const workTypes: { key: WorkType; label: string }[] = [
  { key: 'REQ', label: '需求 (REQ)' },
  { key: 'TASK', label: '任务 (TASK)' },
  { key: 'BUG', label: '缺陷 (BUG)' },
];

export function StatusTemplatePage() {
  const accessCommand = useAccessCommand();
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
    <Card title="状态模版管理">
      <Tabs
        activeKey={activeType}
        onChange={(k) => setActiveType(k as WorkType)}
        items={workTypes.map((wt) => ({ key: wt.key, label: wt.label }))}
      />

      {templatesLoading ? <Spin /> : (
        <>
          <TemplateSelector
            templates={templates}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onCreate={(name) => accessCommand(
              'READ_WRITE',
              '新建状态模版',
              () => createTemplateMut.mutate({ workType: activeType, name }),
            )}
            onSetDefault={(id) => accessCommand(
              'READ_WRITE',
              '设置默认状态模版',
              () => updateTemplateMut.mutate({ id, data: { isDefault: true } }),
            )}
            onDelete={(id) => accessCommand('READ_WRITE', '删除状态模版', () => {
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
                  '添加状态节点',
                  () => createNodeMut.mutate(data),
                )}
                onUpdate={(nodeId, data) => accessCommand(
                  'READ_WRITE',
                  '编辑状态节点',
                  () => updateNodeMut.mutate({ nodeId, data }),
                )}
                onDelete={(nodeId) => accessCommand(
                  'READ_WRITE',
                  '删除状态节点',
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
                  '添加状态流转',
                  () => createTransitionMut.mutate(data),
                )}
                onUpdate={(tid, data) => accessCommand(
                  'READ_WRITE',
                  '编辑状态流转',
                  () => updateTransitionMut.mutate({ tid, data }),
                )}
                onDelete={(tid) => accessCommand(
                  'READ_WRITE',
                  '删除状态流转',
                  () => deleteTransitionMut.mutate(tid),
                )}
                createLoading={createTransitionMut.isPending}
                updateLoading={updateTransitionMut.isPending}
              />
            </div>
          ) : <Empty description="暂无模版" style={{ marginTop: 24 }} />}
        </>
      )}
    </Card>
  );
}
