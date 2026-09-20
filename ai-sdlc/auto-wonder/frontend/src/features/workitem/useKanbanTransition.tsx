import { useRef, useState } from 'react';
import { Button, Modal, Space, Typography, message } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { Workitem, WorkitemDetail } from '@/shared/types/workitem';
import { getTemplateDetail } from '@/features/statemachine/api';
import type { StatusNode, TemplateDetail } from '@/features/statemachine/types';
import { getWorkitem, transitionWorkitem } from './api';
import { classifyWorkitemStatus, STATUS_COLUMNS } from './constants';

/** Use the same classification as the board, including the derived human-decision flag. */
export function kanbanTransitionTargets(item: WorkitemDetail, template: TemplateDetail, column: string): StatusNode[] {
  const targets = new Set(template.transitions
    .filter(edge => String(edge.fromNodeId) === String(item.statusNodeId))
    .map(edge => String(edge.toNodeId)));
  return template.nodes.filter(node => targets.has(String(node.id))
    && String(node.id) !== String(item.statusNodeId)
    && classifyWorkitemStatus({ statusName: node.name, pendingDecision: item.pendingDecision }) === column);
}

export function useKanbanTransition() {
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const locked = useRef(false);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [choice, setChoice] = useState<{ item: WorkitemDetail; nodes: StatusNode[] } | null>(null);

  const release = () => {
    locked.current = false;
    setBusy(false);
    setChoice(null);
  };
  const refresh = async (id: number) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['workitems'] }),
      queryClient.invalidateQueries({ queryKey: ['workitem', id] }),
    ]);
  };
  const commit = async (item: WorkitemDetail, node: StatusNode) => {
    setSaving(true);
    try {
      await accessCommand('READ_WRITE', '流转工单状态', async () => {
        await transitionWorkitem(item.id, node.id, {
          fromNodeId: item.statusNodeId!, expectedVersion: item.version,
        });
        message.success(`状态已更新为「${node.name}」`);
      });
    } catch (error) {
      message.error(error instanceof Error ? error.message : '状态更新失败，请重试');
    } finally {
      await refresh(item.id);
      setSaving(false);
      release();
    }
  };

  const move = (dragged: Workitem, column: string) => {
    if (locked.current || classifyWorkitemStatus(dragged) === column) return;
    return accessCommand('READ_WRITE', '流转工单状态', async () => {
      locked.current = true;
      setBusy(true);
      try {
        // Fetch fresh state and rules; never infer permission from the visual column alone.
        const item = await getWorkitem(dragged.id);
        if (item.version !== dragged.version || String(item.statusNodeId) !== String(dragged.statusNodeId)) {
          throw new Error('工单已被更新，请刷新后重新拖拽');
        }
        if (item.templateId == null || item.statusNodeId == null) {
          throw new Error('工单未配置状态流程，无法拖拽流转');
        }
        const template = await getTemplateDetail(item.templateId);
        const nodes = kanbanTransitionTargets(item, template, column);
        if (nodes.length === 0) {
          const title = STATUS_COLUMNS.find(col => col.key === column)?.title ?? column;
          throw new Error(`当前状态「${item.statusName ?? '未知'}」不能流转到「${title}」，请检查状态流程或在详情页处理待决策事项`);
        }
        if (nodes.length === 1) {
          await commit(item, nodes[0]);
        } else {
          setChoice({ item, nodes });
        }
      } catch (error) {
        message.error(error instanceof Error ? error.message : '状态检查失败，请重试');
        await refresh(dragged.id);
        release();
      }
    });
  };

  const dialog = (
    <Modal title="选择目标状态" open={!!choice} footer={null} onCancel={release}
      closable={!saving} maskClosable={!saving} keyboard={!saving}>
      <Typography.Paragraph>当前状态「{choice?.item.statusName}」可流转到以下状态：</Typography.Paragraph>
      <Space wrap>
        {choice?.nodes.map(node => (
          <Button key={String(node.id)} disabled={saving} onClick={() => void commit(choice.item, node)}>
            {node.name}
          </Button>
        ))}
      </Space>
    </Modal>
  );
  return { move, busy, dialog };
}
