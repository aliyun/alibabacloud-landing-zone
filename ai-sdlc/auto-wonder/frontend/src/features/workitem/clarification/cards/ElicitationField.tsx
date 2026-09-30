import { useEffect, useRef, type ReactNode } from 'react';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { Checkbox, Input, InputNumber, Radio, Switch } from 'antd';
import { CLARIFICATION_THEME } from '../theme';
import { OTHER_SENTINEL } from './elicitationAnswer';
import type { ElicitationField as ElicitationFieldType, ElicitationOption } from './schemaForm';

export interface ElicitationFieldProps {
  field: ElicitationFieldType;
  /** select: string|OTHER；multiselect: string[]（可含 OTHER）；其余为原值 */
  value: unknown;
  otherText: string;
  error?: string;
  disabled?: boolean;
  onValueChange: (value: unknown) => void;
  onOtherTextChange: (text: string) => void;
  /** 单选点中真实选项时触发，供向导自动切下一题；选「有其他想法」不触发。 */
  onPickOption?: (optionValue: string) => void;
}

const optionBlockStyle = {
  display: 'flex' as const,
  alignItems: 'flex-start' as const,
  padding: '10px 12px',
  lineHeight: 1.7,
  marginBottom: 8,
  borderWidth: 1, borderStyle: 'solid', borderColor: CLARIFICATION_THEME.controlBorder,
  borderRadius: CLARIFICATION_THEME.radiusBlock,
  backgroundColor: CLARIFICATION_THEME.surface,
  whiteSpace: 'normal' as const,
};

// 「有其他想法」末项：与真实选项同款边框块，仅把边框改成虚线以示区分。
// 提为模块常量，避免每次渲染新建对象，也保证单选/多选两处完全一致。
const otherOptionBlockStyle = { ...optionBlockStyle, borderStyle: 'dashed' as const };

function OptionLabel({ option }: { option: ElicitationOption }) {
  return (
    <span style={{ fontSize: 16 }}>
      {option.label}
      {option.hint ? (
        <span style={{ display: 'block', fontSize: 14, color: CLARIFICATION_THEME.textMuted }}>{option.hint}</span>
      ) : null}
    </span>
  );
}

export function ElicitationField({
  field, value, otherText, error, disabled, onValueChange, onOtherTextChange, onPickOption,
}: ElicitationFieldProps) {
  const otherInputRef = useRef<TextAreaRef>(null);

  const isOtherSelected = field.control === 'select'
    ? value === OTHER_SENTINEL
    : field.control === 'multiselect'
      ? ((value as string[] | undefined) ?? []).includes(OTHER_SENTINEL)
      : false;

  // 只在「同一题内 OTHER 从未选变为选中」（用户刚点了「有其他想法」）时聚焦，浏览器随之把输入框滚到可见；
  // 换题回看已选 OTHER 的题目是「挂载即已选」，不抢焦点，免得夺走刚点的步骤点按钮焦点（对齐 mockup 的 inp.focus()）。
  const prevOther = useRef({ name: field.name, other: isOtherSelected });
  useEffect(() => {
    if (prevOther.current.name === field.name && !prevOther.current.other && isOtherSelected) {
      otherInputRef.current?.focus();
    }
    prevOther.current = { name: field.name, other: isOtherSelected };
  });

  const otherInput = (
    <Input.TextArea
      ref={otherInputRef}
      value={otherText}
      disabled={disabled}
      rows={2}
      placeholder="输入其他想法"
      data-testid={`elicitation-other-input-${field.name}`}
      onChange={(e) => onOtherTextChange(e.target.value)}
      style={{ marginTop: 6 }}
    />
  );

  let control: ReactNode;

  if (field.control === 'select') {
    control = (
      <>
        <Radio.Group
          value={value as string | undefined}
          disabled={disabled}
          onChange={(e) => {
            const next = e.target.value as string;
            onValueChange(next);
            if (next !== OTHER_SENTINEL) onPickOption?.(next);
          }}
          style={{ display: 'block' }}
        >
          {(field.options ?? []).map((option, index) => (
            <Radio
              key={option.value}
              value={option.value}
              data-testid={`elicitation-option-${field.name}-${index}`}
              style={optionBlockStyle}
            >
              <OptionLabel option={option} />
            </Radio>
          ))}
          <Radio
            key={OTHER_SENTINEL}
            value={OTHER_SENTINEL}
            data-testid={`elicitation-option-${field.name}-other`}
            style={otherOptionBlockStyle}
          >
            <span style={{ fontSize: 16 }}>有其他想法</span>
          </Radio>
        </Radio.Group>
        {value === OTHER_SENTINEL ? otherInput : null}
      </>
    );
  } else if (field.control === 'multiselect') {
    const arr = (value as string[] | undefined) ?? [];
    control = (
      <>
        <Checkbox.Group
          value={arr}
          disabled={disabled}
          onChange={(next) => onValueChange(next as string[])}
          style={{ display: 'block' }}
        >
          {(field.options ?? []).map((option, index) => (
            <Checkbox
              key={option.value}
              value={option.value}
              data-testid={`elicitation-option-${field.name}-${index}`}
              style={optionBlockStyle}
            >
              <OptionLabel option={option} />
            </Checkbox>
          ))}
          <Checkbox
            key={OTHER_SENTINEL}
            value={OTHER_SENTINEL}
            data-testid={`elicitation-option-${field.name}-other`}
            style={otherOptionBlockStyle}
          >
            <span style={{ fontSize: 16 }}>有其他想法</span>
          </Checkbox>
        </Checkbox.Group>
        {arr.includes(OTHER_SENTINEL) ? otherInput : null}
      </>
    );
  } else if (field.control === 'boolean') {
    control = (
      <Switch
        checked={value === true}
        disabled={disabled}
        data-testid={`elicitation-field-${field.name}`}
        onChange={(checked) => onValueChange(checked)}
      />
    );
  } else if (field.control === 'number') {
    control = (
      <InputNumber
        value={value as number | undefined}
        disabled={disabled}
        aria-label={field.label}
        data-testid={`elicitation-field-${field.name}`}
        onChange={(next) => onValueChange(next ?? undefined)}
        style={{ width: '100%' }}
      />
    );
  } else if (field.control === 'json') {
    control = (
      <>
        <Input.TextArea
          value={(value as string) ?? ''}
          disabled={disabled}
          rows={3}
          aria-label={field.label}
          data-testid={`elicitation-field-${field.name}`}
          onChange={(e) => onValueChange(e.target.value)}
        />
        {field.rawSchema !== undefined ? (
          <details style={{ marginTop: 4 }}>
            <summary style={{ cursor: 'pointer', fontSize: 14, color: CLARIFICATION_THEME.textMuted }}>原始 Schema</summary>
            <pre
              data-testid={`elicitation-raw-schema-${field.name}`}
              style={{
                fontSize: 14, whiteSpace: 'pre-wrap', maxHeight: 150, overflow: 'auto',
                padding: '6px 10px', color: CLARIFICATION_THEME.textSecondary,
                backgroundColor: CLARIFICATION_THEME.codeSurface,
                border: `1px solid ${CLARIFICATION_THEME.codeBorder}`,
                borderRadius: CLARIFICATION_THEME.radiusBlock, margin: '2px 0 0',
              }}
            >
              {JSON.stringify(field.rawSchema, null, 2)}
            </pre>
          </details>
        ) : null}
      </>
    );
  } else {
    control = (
      <Input.TextArea
        value={(value as string) ?? ''}
        disabled={disabled}
        rows={2}
        aria-label={field.label}
        data-testid={`elicitation-field-${field.name}`}
        onChange={(e) => onValueChange(e.target.value)}
      />
    );
  }

  return (
    <div>
      {control}
      {error ? (
        <div
          data-testid={`elicitation-error-${field.name}`}
          style={{ color: 'var(--aw-error)', fontSize: 14, marginTop: 2 }}
        >
          {error}
        </div>
      ) : null}
    </div>
  );
}
