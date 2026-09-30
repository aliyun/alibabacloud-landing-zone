import { useEffect, useMemo, useRef, useState } from 'react';
import { Button } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { CLARIFICATION_THEME } from '../theme';
import { parseElicitationSchema } from './schemaForm';
import { ElicitationField } from './ElicitationField';
import type { ElicitationReply } from './ElicitationCard';
import {
  OTHER_SENTINEL,
  computeElicitationContent,
  isFieldAnswered,
  validateElicitation,
  type AnswerValues,
  type OtherText,
} from './elicitationAnswer';

const PRIMARY = 'var(--aw-accent-text)';
const AUTO_ADVANCE_MS = 300;

interface ElicitationWizardProps {
  requestId: string;
  message?: string;
  schema: unknown;
  submitting?: boolean;
  /** 全屏时面板左右留白变为 24px，卡片随之对齐，保持与消息/输入框一致的视觉边距。 */
  fullscreen?: boolean;
  onReply: (reply: ElicitationReply) => void;
}

export function ElicitationWizard({
  requestId, message, schema, submitting = false, fullscreen = false, onReply,
}: ElicitationWizardProps) {
  const form = useMemo(() => parseElicitationSchema(schema), [schema]);
  const fields = form.fields;
  const total = fields.length;

  const [stepIndex, setStepIndex] = useState(0);
  const [values, setValues] = useState<AnswerValues>({});
  const [otherText, setOtherText] = useState<OtherText>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const autoAdvanceTimer = useRef<number | null>(null);

  useEffect(
    () => () => { if (autoAdvanceTimer.current) window.clearTimeout(autoAdvanceTimer.current); },
    [],
  );

  const clearAutoAdvance = () => {
    if (autoAdvanceTimer.current) {
      window.clearTimeout(autoAdvanceTimer.current);
      autoAdvanceTimer.current = null;
    }
  };

  const clearError = (name: string) => setFieldErrors((prev) => {
    if (!(name in prev)) return prev;
    const next = { ...prev };
    delete next[name];
    return next;
  });

  const handleValueChange = (name: string, control: string, value: unknown) => {
    setValues((prev) => ({ ...prev, [name]: value }));
    // 单选改选真实项即与「有其他想法」互斥，清空补充文本。
    if (control === 'select' && value !== OTHER_SENTINEL) {
      setOtherText((prev) => ({ ...prev, [name]: '' }));
    }
    clearError(name);
  };

  const handleOtherTextChange = (name: string, text: string) => {
    setOtherText((prev) => ({ ...prev, [name]: text }));
    clearError(name);
  };

  const handlePickOption = (fromStep: number) => {
    clearAutoAdvance();
    autoAdvanceTimer.current = window.setTimeout(() => {
      autoAdvanceTimer.current = null;
      setStepIndex((cur) => (cur === fromStep && cur < total - 1 ? cur + 1 : cur));
    }, AUTO_ADVANCE_MS);
  };

  const submit = () => {
    const { errors, firstInvalidIndex } = validateElicitation(fields, values, otherText);
    if (firstInvalidIndex >= 0) {
      setFieldErrors(errors);
      setStepIndex(firstInvalidIndex);
      return;
    }
    setFieldErrors({});
    onReply({ action: 'accept', content: computeElicitationContent(fields, values, otherText) });
  };

  const goNext = () => {
    const field = fields[stepIndex];
    if (!field) return;
    const { errors } = validateElicitation([field], values, otherText);
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }
    if (stepIndex === total - 1) submit();
    else setStepIndex((cur) => cur + 1);
  };

  const gotoStep = (index: number) => {
    if (index < 0 || index >= total) return;
    clearAutoAdvance();
    setStepIndex(index);
  };

  const field = fields[stepIndex];
  if (!field) return null;
  const isLast = stepIndex === total - 1;

  return (
    <div
      data-testid={`elicitation-wizard-${requestId}`}
      aria-label={message || form.title || '需要你的确认'}
      style={{
        margin: fullscreen ? '12px 24px' : '8px 12px',
        maxHeight: fullscreen ? 'min(60%, 480px)' : 'min(56%, 360px)',
        display: 'flex', flexDirection: 'column', flexShrink: 0,
        backgroundColor: CLARIFICATION_THEME.surface,
        border: `1px solid ${CLARIFICATION_THEME.controlBorder}`,
        borderRadius: CLARIFICATION_THEME.radiusControl,
        boxShadow: 'none',
        overflow: 'hidden',
      }}
    >
      <div style={{ borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`, flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 16px 8px' }}>
          <span style={{
            fontSize: 16, fontWeight: 600, flex: 1, minWidth: 0,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>
            {message || form.title || '需要你的确认'}
          </span>
          <Button
            type="text"
            size="small"
            disabled={submitting}
            data-testid={`elicitation-skip-${requestId}`}
            onClick={() => onReply({ action: 'decline' })}
            style={{ fontSize: 14, color: CLARIFICATION_THEME.textMuted }}
          >
            跳过
          </Button>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 16px 12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {fields.map((f, i) => {
              const current = i === stepIndex;
              const answered = isFieldAnswered(f, values, otherText);
              return (
                <button
                  key={f.name}
                  type="button"
                  data-testid={`elicitation-step-${requestId}-${i}`}
                  title={`第${i + 1}题：${f.label}`}
                  onClick={() => gotoStep(i)}
                  style={{
                    width: 28, height: 28, padding: 0, borderRadius: '50%', cursor: 'pointer',
                    fontSize: 14, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    border: `1px solid ${current || answered ? PRIMARY : CLARIFICATION_THEME.controlBorder}`,
                    backgroundColor: current ? 'var(--aw-primary)' : (answered ? 'rgba(var(--aw-accent-rgb),.10)' : CLARIFICATION_THEME.surface),
                    color: current ? 'var(--aw-primary-ink)' : (answered ? PRIMARY : CLARIFICATION_THEME.textSecondary),
                    fontWeight: current ? 600 : 400,
                  }}
                >
                  {i + 1}
                </button>
              );
            })}
          </div>
          <span
            data-testid={`elicitation-count-${requestId}`}
            style={{ fontSize: 14, color: CLARIFICATION_THEME.textSecondary }}
          >
            第 {stepIndex + 1}/{total} 题
          </span>
        </div>
      </div>

      <div style={{ padding: 16, lineHeight: 1.7, overflow: 'auto', flex: 1, minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <QuestionCircleOutlined style={{ color: PRIMARY }} />
          <span style={{ fontSize: 16, fontWeight: 600 }}>{field.label}</span>
          {field.required ? <span style={{ color: 'var(--aw-error)' }}> *</span> : null}
        </div>
        {field.description ? (
          <div style={{
            fontSize: 14, color: CLARIFICATION_THEME.textSecondary,
            whiteSpace: 'pre-wrap', margin: '4px 0 8px',
          }}>
            {field.description}
          </div>
        ) : null}
        <ElicitationField
          field={field}
          value={values[field.name]}
          otherText={otherText[field.name] ?? ''}
          error={fieldErrors[field.name]}
          disabled={submitting}
          onValueChange={(value) => handleValueChange(field.name, field.control, value)}
          onOtherTextChange={(text) => handleOtherTextChange(field.name, text)}
          onPickOption={field.control === 'select' ? () => handlePickOption(stepIndex) : undefined}
        />
      </div>

      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px',
        borderTop: `1px solid ${CLARIFICATION_THEME.hairline}`,
        backgroundColor: CLARIFICATION_THEME.codeSurface, flexShrink: 0,
      }}>
        <Button
          size="small"
          disabled={submitting || stepIndex === 0}
          data-testid={`elicitation-prev-${requestId}`}
          onClick={() => gotoStep(stepIndex - 1)}
        >
          上一题
        </Button>
        {isLast ? (
          <Button
            type="primary"
            size="small"
            disabled={submitting}
            data-testid={`elicitation-submit-${requestId}`}
            onClick={submit}
            style={{ marginLeft: 'auto' }}
          >
            提交
          </Button>
        ) : (
          <Button
            type="primary"
            size="small"
            disabled={submitting}
            data-testid={`elicitation-next-${requestId}`}
            onClick={goNext}
            style={{ marginLeft: 'auto' }}
          >
            下一题
          </Button>
        )}
      </div>
    </div>
  );
}
