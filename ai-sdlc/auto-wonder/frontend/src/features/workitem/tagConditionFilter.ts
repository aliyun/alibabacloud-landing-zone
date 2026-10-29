/** 标签逻辑运算符：AND=同时包含，OR/IN=命中任一，NOT=全部排除。 */
export type WorkitemTagConditionOp = 'AND' | 'OR' | 'IN' | 'NOT';

export interface WorkitemTagCondition {
  op: WorkitemTagConditionOp;
  tags: string[];
}

/** rowId 仅用于 React 列表 key，序列化时剥离，不进入请求。 */
export interface WorkitemTagConditionDraft extends WorkitemTagCondition {
  rowId: number;
}

export const TAG_CONDITION_LIMIT = 30;
export const TAG_CONDITIONS_MAX_LENGTH = 4000;

export function createEmptyTagCondition(rowId: number): WorkitemTagConditionDraft {
  return { rowId, op: 'AND', tags: [] };
}

/** 序列化为服务端参数：条件之间恒为 AND，空数组表示不过滤。 */
export function serializeTagConditions(conditions: WorkitemTagConditionDraft[]): string | undefined {
  if (conditions.length === 0) {
    return undefined;
  }
  return JSON.stringify(conditions.map(({ op, tags }) => ({ op, tags })));
}

export function hasIncompleteTagCondition(conditions: WorkitemTagCondition[]): boolean {
  return conditions.some((condition) => condition.tags.length === 0);
}

/** GET URL 保护：序列化结果超过上限时页面不提交查询。 */
export function isTagConditionsTooLong(conditions: WorkitemTagConditionDraft[]): boolean {
  const serialized = serializeTagConditions(conditions);
  return serialized !== undefined && serialized.length > TAG_CONDITIONS_MAX_LENGTH;
}
