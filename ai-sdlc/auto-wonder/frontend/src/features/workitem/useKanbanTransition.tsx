import { useRef, useState } from 'react';
import { Button, Modal, Space, Typography, message } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { Workitem, WorkitemDetail } from '@/shared/types/workitem';
import { getTemplateDetail } from '@/features/statemachine/api';
import type { StatusNode, TemplateDetail } from '@/features/statemachine/types';
import { getWorkitem, transitionWorkitem } from './api';
import { statusCategoryOf, STATUS_COLUMNS } from './constants';

/**
 * 节点类别 → 看板列（规格 3.1：状态名称不参与判断）。
 * 未结束类别（INIT/IN_PROGRESS）在需人工（与「待决策」同条件）时落在待决策列。
 */
export function nodeColumn(node: StatusNode, pendingDecision?: boolean | null): string {
  const category = (node.category ?? '').toUpperCase();
  if (category === 'DONE') return 'DONE';
  if (category === 'INIT' || category === 'IN_PROGRESS') {
    return pendingDecision ? 'PENDING_DECISION' : (category === 'INIT' ? 'NEW' : 'IN_PROGRESS');
  }
  return 'CANCELED';
}

/**
 * 流转候选（规格 3.3）：当前模板内落在目标看板列的全部节点。
 * transitions 只是推荐集、不拦截；推荐目标排在候选前面，非推荐流转由服务端放行并返回警告。
 *
 * 终态（DONE/CANCELED）工单的 pendingDecision 被终态类别掩蔽，流转后的实际落列
 * 由服务端按派发事实分类决定（规格 3.1），前端不用流转前的值预测：重开拖到任一
 * 未结束列时，未结束节点都作为候选，提交后以服务端返回的 statusCategory 为准。
 */
export function kanbanTransitionTargets(item: WorkitemDetail, template: TemplateDetail, column: string): StatusNode[] {
  const recommended = recommendedTargetIds(item, template);
  const reopenFromTerminal = TERMINAL_CATEGORIES.has(statusCategoryOf(item))
    && UNFINISHED_COLUMNS.has(column);
  return template.nodes
    .filter(node => String(node.id) !== String(item.statusNodeId)
      && (reopenFromTerminal
        ? isUnfinishedCategory(node)
        : nodeColumn(node, item.pendingDecision) === column))
    .sort((a, b) => Number(recommended.has(String(b.id))) - Number(recommended.has(String(a.id))));
}

const TERMINAL_CATEGORIES = new Set(['DONE', 'CANCELED']);
const UNFINISHED_COLUMNS = new Set(['NEW', 'IN_PROGRESS', 'PENDING_DECISION']);

function isUnfinishedCategory(node: StatusNode): boolean {
  const category = (node.category ?? '').toUpperCase();
  return category === 'INIT' || category === 'IN_PROGRESS';
}

function recommendedTargetIds(item: WorkitemDetail, template: TemplateDetail): Set<string> {
  return new Set(template.transitions
    .filter(edge => String(edge.fromNodeId) === String(item.statusNodeId))
    .map(edge => String(edge.toNodeId)));
}

export function useKanbanTransition() {
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const locked = useRef(false);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [choice, setChoice] = useState<{ item: WorkitemDetail; nodes: StatusNode[]; recommendedIds: Set<string> } | null>(null);

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
        const updated = await transitionWorkitem(item.id, node.id, {
          fromNodeId: item.statusNodeId!, expectedVersion: item.version,
        });
        message.success(`状态已更新为「${node.name}」`);
        // 越界流转放行但提示（规格 3.3）：警告由服务端在响应中下发。
        if (updated.transitionWarning) {
          message.warning(updated.transitionWarning);
        }
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
    if (locked.current || statusCategoryOf(dragged) === column) return;
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
          throw new Error(`当前模板没有可流转到「${title}」的状态节点，请在详情页处理或联系管理员调整模板`);
        }
        const recommendedIds = recommendedTargetIds(item, template);
        // 终态重开必须显式选择状态：流转后的落列由服务端按派发事实分类，
        // 自动提交唯一推荐节点会把卡片写到与拖拽目标不同的列。
        const reopenFromTerminal = TERMINAL_CATEGORIES.has(statusCategoryOf(item))
          && UNFINISHED_COLUMNS.has(column);
        const preferred = nodes.filter(node => recommendedIds.has(String(node.id)));
        if (!reopenFromTerminal && (preferred.length === 1 || (preferred.length === 0 && nodes.length === 1))) {
          await commit(item, preferred[0] ?? nodes[0]);
        } else {
          setChoice({ item, nodes, recommendedIds });
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
          <Button key={String(node.id)} disabled={saving}
            type={choice.recommendedIds.has(String(node.id)) ? 'primary' : 'default'}
            onClick={() => void commit(choice.item, node)}>
            {node.name}
          </Button>
        ))}
      </Space>
    </Modal>
  );
  return { move, busy, dialog };
}
