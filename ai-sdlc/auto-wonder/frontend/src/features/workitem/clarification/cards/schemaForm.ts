import { i18n } from '@/shared/i18n';
export type ElicitationControl = 'select' | 'multiselect' | 'text' | 'boolean' | 'number' | 'json';

export interface ElicitationOption {
  value: string;
  label: string;
  hint?: string;
}

export interface ElicitationField {
  name: string;
  label: string;
  control: ElicitationControl;
  required: boolean;
  description?: string;
  options?: ElicitationOption[];
  /** 降级为 JSON 输入时把原始子 schema 带出来，供折叠展示。 */
  rawSchema?: unknown;
}

export interface ElicitationForm {
  title?: string;
  fields: ElicitationField[];
  fallback: boolean;
  raw: unknown;
}

const FALLBACK_FIELD_NAME = '_raw';

export function elicitationMessage(message: string | undefined, schema: unknown): string | undefined {
  // Only Runtime-owned headings carry this intent; raw and historical questions stay unchanged.
  return asRecord(schema)?.['x-autowonder-message-kind'] === 'CONFIRMATION_REQUIRED'
    ? i18n.t('workitem:clarification.confirmation') : message;
}

export function parseElicitationSchema(schema: unknown): ElicitationForm {
  const root = asRecord(schema);
  const properties = root && root.type === 'object' ? asRecord(root.properties) : null;
  const names = properties ? Object.keys(properties) : [];
  if (!properties || names.length === 0) {
    return fallbackForm(schema);
  }

  const required = Array.isArray(root?.required) ? (root!.required as unknown[]) : [];
  const fields = names.map((name) =>
    toField(name, properties[name], required.includes(name)),
  );

  return {
    title: typeof root?.title === 'string' ? root.title : undefined,
    fields,
    fallback: false,
    raw: schema,
  };
}

function fallbackForm(schema: unknown): ElicitationForm {
  // 认不出的 schema 不能白屏：降级成 JSON 文本域，用户仍能作答。
  return {
    title: undefined,
    fields: [
      {
        name: FALLBACK_FIELD_NAME,
        label: i18n.t('workitem:clarification.jsonAnswer'),
        control: 'json',
        required: true,
        rawSchema: schema,
      },
    ],
    fallback: true,
    raw: schema,
  };
}

function toField(name: string, rawField: unknown, required: boolean): ElicitationField {
  const field = asRecord(rawField);
  const label = typeof field?.title === 'string' ? field.title : name;
  const description = typeof field?.description === 'string' ? field.description : undefined;
  const base: ElicitationField = { name, label, control: 'text', required };

  if (field?.type === 'string') {
    const options = toOptions(field.oneOf) ?? toOptions(Array.isArray(field.enum) ? field.enum.map(value => ({ const: value })) : undefined);
    if (!options) {
      return { ...base, description };
    }
    const split = splitOptionHints(description, options);
    return { ...base, control: 'select', description: split, options };
  }

  if (field?.type === 'boolean') {
    return { ...base, control: 'boolean', description };
  }

  if (field?.type === 'number' || field?.type === 'integer') {
    return { ...base, control: 'number', description };
  }

  if (field?.type === 'array') {
    // 实测 qodercli 多选题把选项放在 items.anyOf；保留 oneOf 兼容旧载荷与合成夹具。
    const items = asRecord(field.items);
    const options = toOptions(items?.anyOf) ?? toOptions(items?.oneOf)
      ?? toOptions(Array.isArray(items?.enum) ? items.enum.map(value => ({ const: value })) : undefined);
    if (options) {
      const split = splitOptionHints(description, options);
      return { ...base, control: 'multiselect', description: split, options };
    }
  }

  return { ...base, control: 'json', description, rawSchema: rawField };
}

function toOptions(rawOneOf: unknown): ElicitationOption[] | null {
  if (!Array.isArray(rawOneOf) || rawOneOf.length === 0) return null;
  const options: ElicitationOption[] = [];
  for (const entry of rawOneOf) {
    const item = asRecord(entry);
    if (item?.const === undefined || item.const === null) continue;
    const value = String(item.const);
    options.push({ value, label: typeof item.title === 'string' ? item.title : value });
  }
  return options.length > 0 ? options : null;
}

/**
 * Qoder 把每个选项的解释逐行塞进 description，不拆的话线上会把一大坨
 * 说明文字糊在问题标题里。首行是问题正文，后续 `<选项标签>: <说明>`
 * 行挂到对应选项，认不出的行回落拼回正文。
 */
function splitOptionHints(
  description: string | undefined,
  options: ElicitationOption[],
): string | undefined {
  if (!description) return undefined;
  const [first, ...rest] = description.split('\n');
  const body = [first];
  const byLongestLabel = [...options].sort((a, b) => b.label.length - a.label.length);

  for (const line of rest) {
    const matched = byLongestLabel.find(
      (option) => line.startsWith(`${option.label}:`) || line.startsWith(`${option.label}：`),
    );
    const hint = matched ? line.slice(matched.label.length + 1).trim() : '';
    if (matched && hint) {
      matched.hint = hint;
    } else {
      body.push(line);
    }
  }

  return body.join('\n');
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}
