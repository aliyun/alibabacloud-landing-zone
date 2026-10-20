import { i18n } from '@/shared/i18n';
import type { ElicitationField } from './schemaForm';

/** 「有其他想法」在控件 value 里的哨兵值；不会出现在回传载荷里。 */
export const OTHER_SENTINEL = '__other__';

export type AnswerValues = Record<string, unknown>;
export type OtherText = Record<string, string>;

function isBlank(value: unknown): boolean {
  if (value === undefined || value === null || value === '') return true;
  return Array.isArray(value) && value.length === 0;
}

function trimmed(other: OtherText, name: string): string {
  return (other[name] ?? '').trim();
}

function selectedArray(value: unknown): string[] {
  return (value as string[] | undefined) ?? [];
}

/** 该字段是否已作答；供步骤点「已答」态、推进校验与提交校验复用。 */
export function isFieldAnswered(field: ElicitationField, values: AnswerValues, other: OtherText): boolean {
  const value = values[field.name];
  if (field.control === 'select') {
    return value === OTHER_SENTINEL ? trimmed(other, field.name) !== '' : !isBlank(value);
  }
  if (field.control === 'multiselect') {
    const arr = selectedArray(value);
    const real = arr.filter((x) => x !== OTHER_SENTINEL);
    return real.length > 0 || (arr.includes(OTHER_SENTINEL) && trimmed(other, field.name) !== '');
  }
  return !isBlank(value);
}

/**
 * 把作答编码回 schema 已声明的原字段：单选替换、多选追加自定义值、文本逐字。
 * 绝不产生 q0_note 等未声明顶层字段——实测 Qoder 会过滤掉。
 */
export function computeElicitationContent(
  fields: ElicitationField[],
  values: AnswerValues,
  other: OtherText,
): Record<string, unknown> {
  const content: Record<string, unknown> = {};

  for (const field of fields) {
    const value = values[field.name];
    const note = trimmed(other, field.name);

    if (field.control === 'json') {
      if (isBlank(value)) continue;
      try {
        const parsed = JSON.parse(String(value));
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          Object.assign(content, parsed as Record<string, unknown>);
        }
      } catch {
        // 非法 JSON 不回传，交由校验拦截
      }
      continue;
    }

    if (field.control === 'select') {
      if (value === OTHER_SENTINEL) {
        if (note) content[field.name] = note;
      } else if (!isBlank(value)) {
        content[field.name] = value;
      }
      continue;
    }

    if (field.control === 'multiselect') {
      const all = selectedArray(value);
      const arr = all.filter((x) => x !== OTHER_SENTINEL);
      if (all.includes(OTHER_SENTINEL) && note) arr.push(note);
      if (arr.length > 0) content[field.name] = arr;
      continue;
    }

    // text / boolean / number：原值逐字（false、0 不算空）
    if (!isBlank(value)) content[field.name] = value;
  }

  return content;
}

export interface ElicitationValidation {
  errors: Record<string, string>;
  /** 第一个未通过必填校验的题序，全通过为 -1。 */
  firstInvalidIndex: number;
}

export function validateElicitation(
  fields: ElicitationField[],
  values: AnswerValues,
  other: OtherText,
): ElicitationValidation {
  const errors: Record<string, string> = {};
  let firstInvalidIndex = -1;

  fields.forEach((field, index) => {
    if (!field.required) return;
    if (isFieldAnswered(field, values, other)) return;

    const value = values[field.name];
    const otherOnly = field.control === 'select'
      ? value === OTHER_SENTINEL
      : field.control === 'multiselect' && selectedArray(value).includes(OTHER_SENTINEL);
    errors[field.name] = otherOnly ? i18n.t('workitem:clarification.otherRequired') : i18n.t('workitem:clarification.fieldRequired', { label: field.label });
    if (firstInvalidIndex === -1) firstInvalidIndex = index;
  });

  return { errors, firstInvalidIndex };
}
