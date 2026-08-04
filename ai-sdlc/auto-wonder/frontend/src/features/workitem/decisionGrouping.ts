import { classifyWorkitemStatus } from './constants';
import type { Workitem } from '@/shared/types/workitem';

/**
 * 待决策按人分类工具。
 *
 * 工单「待决策」状态由 {@link classifyWorkitemStatus} 判定（综合后端 pendingDecision 标记与状态名）。
 * 本模块在此基础上提供「按决策人分组」与「只看当前登录人决策」两个纯函数，供看板与单测复用。
 */

export interface DecisionGroup {
  /** 分组键：assigneeType:assigneeRef，未指派统一为 'null:null' */
  key: string;
  /** 分组展示名：assigneeDisplayName || assigneeName || '未指派' */
  label: string;
  items: Workitem[];
}

const UNASSIGNED_LABEL = '未指派';

export function isPendingDecision(item: Workitem): boolean {
  return classifyWorkitemStatus(item) === 'PENDING_DECISION';
}

/**
 * 判断工单是否为「需要当前登录人决策」的工单：
 * 必须处于待决策状态，且指派给人类用户、且指派引用等于当前用户 id。
 */
export function isMyPendingDecision(
  item: Workitem,
  userId: number | null | undefined,
): boolean {
  if (userId == null) return false;
  return isPendingDecision(item) && item.assigneeType === 'HUMAN' && item.assigneeRef === userId;
}

function assigneeKey(item: Workitem): string {
  return `${item.assigneeType ?? 'HUMAN'}:${item.assigneeRef ?? 'null'}`;
}

function assigneeLabel(item: Workitem): string {
  return item.assigneeDisplayName || item.assigneeName || UNASSIGNED_LABEL;
}

/**
 * 将待决策工单按决策人（指派人）分组。未指派的工单归入「未指派」组。
 * 分组按数量降序、名称升序排序，保证展示稳定。
 */
export function groupPendingDecisionsByAssignee(items: Workitem[]): DecisionGroup[] {
  const map = new Map<string, DecisionGroup>();
  for (const item of items) {
    if (!isPendingDecision(item)) continue;
    const key = assigneeKey(item);
    let group = map.get(key);
    if (!group) {
      group = { key, label: assigneeLabel(item), items: [] };
      map.set(key, group);
    }
    group.items.push(item);
  }
  return Array.from(map.values()).sort((a, b) => {
    if (a.items.length !== b.items.length) return b.items.length - a.items.length;
    return a.label.localeCompare(b.label, 'zh-CN');
  });
}
