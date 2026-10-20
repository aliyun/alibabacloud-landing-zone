import { useTranslation } from 'react-i18next';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Button } from 'antd';
import { DownOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { CLARIFICATION_THEME } from '../theme';
import { elicitationMessage, parseElicitationSchema } from './schemaForm';
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
/** 收起/展开的高度过渡时长，与出现/消失动画同一量级，保持卡片动效风格统一。 */
const FOLD_MS = 180;

const reducedMotionPreferred = () =>
  typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

interface ElicitationWizardProps {
  requestId: string;
  message?: string;
  schema: unknown;
  submitting?: boolean;
  /** 全屏时面板左右留白变为 24px，卡片随之对齐，保持与消息/输入框一致的视觉边距。 */
  fullscreen?: boolean;
  /** 由澄清面板显式开启：标题行提供收起/展开入口，卡片带出现/消失动效。
   *  默认关闭，首席助理与派发请求确认两处复用保持现状（无收起入口、无动效）。 */
  collapsible?: boolean;
  /** 答完/跳过后父级在卸载前标记：卡片淡出且不可交互，随后由父级移除。 */
  dismissing?: boolean;
  onReply: (reply: ElicitationReply) => void;
}

export function ElicitationWizard({
  requestId, message, schema, submitting = false, fullscreen = false,
  collapsible = false, dismissing = false, onReply,
}: ElicitationWizardProps) {
  const { t } = useTranslation('workitem');
  const form = useMemo(() => parseElicitationSchema(schema), [schema, t]);
  const heading = elicitationMessage(message, schema) || form.title || t('clarification.confirmation');
  const fields = form.fields;
  const total = fields.length;

  const [stepIndex, setStepIndex] = useState(0);
  const [values, setValues] = useState<AnswerValues>({});
  const [otherText, setOtherText] = useState<OtherText>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, true>>({});
  const [collapsed, setCollapsed] = useState(false);
  const autoAdvanceTimer = useRef<number | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const foldFromHeightRef = useRef<number | null>(null);

  useEffect(
    () => () => { if (autoAdvanceTimer.current) window.clearTimeout(autoAdvanceTimer.current); },
    [],
  );

  const toggleCollapsed = () => {
    if (!collapsible) return;
    const root = rootRef.current;
    // 收起/展开按需求只做条件渲染（收起的区块不留在 DOM 里），高度变化改由一次性
    // 像素过渡承接：切换前记录旧高度，切换后从旧高度过渡到新高度。
    foldFromHeightRef.current = root ? root.offsetHeight : null;
    setCollapsed((cur) => !cur);
  };

  useLayoutEffect(() => {
    const from = foldFromHeightRef.current;
    foldFromHeightRef.current = null;
    const root = rootRef.current;
    // jsdom 无布局（offsetHeight 恒为 0）与「减少动画」偏好下直接跳过，保证瞬时切换。
    if (from == null || !root || reducedMotionPreferred()) return;
    const to = root.offsetHeight;
    if (!from || !to || from === to) return;
    root.style.transition = 'none';
    root.style.height = `${from}px`;
    void root.offsetHeight;
    root.style.transition = `height ${FOLD_MS}ms ease-out`;
    root.style.height = `${to}px`;
    const settle = () => {
      root.style.transition = '';
      root.style.height = '';
    };
    root.addEventListener('transitionend', settle, { once: true });
    const timer = window.setTimeout(settle, FOLD_MS + 80);
    return () => {
      window.clearTimeout(timer);
      root.removeEventListener('transitionend', settle);
      settle();
    };
  }, [collapsed]);

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
      setFieldErrors(Object.fromEntries(Object.keys(errors).map(name => [name, true as const])));
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
      setFieldErrors(Object.fromEntries(Object.keys(errors).map(name => [name, true as const])));
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
  const isCollapsed = collapsible && collapsed;
  const toggleLabel = isCollapsed ? t('clarification.expandCard') : t('clarification.collapseCard');
  // 收起态仍可点击（含提交/跳过在途），不阻塞原有流程，因此不加 disabled。
  const toggleButton = collapsible ? (
    <Button
      type="text"
      size="small"
      className="aw-elicitation-toggle"
      aria-expanded={!isCollapsed}
      aria-label={toggleLabel}
      title={toggleLabel}
      data-testid={`elicitation-toggle-${requestId}`}
      onClick={toggleCollapsed}
      icon={<DownOutlined className="aw-elicitation-chevron" />}
      style={{ fontSize: 12, color: CLARIFICATION_THEME.textMuted, flexShrink: 0 }}
    />
  ) : null;

  return (
    <div
      ref={rootRef}
      data-testid={`elicitation-wizard-${requestId}`}
      aria-label={heading}
      className={collapsible ? `aw-elicitation-card${dismissing ? ' is-dismissing' : ''}` : undefined}
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
      {isCollapsed ? (
      <div
        data-testid={`elicitation-collapsed-${requestId}`}
        style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 16px' }}
      >
        <span style={{
          fontSize: 16, fontWeight: 600, flex: 1, minWidth: 0,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {heading}
        </span>
        <span
          data-testid={`elicitation-count-${requestId}`}
          style={{ fontSize: 14, color: CLARIFICATION_THEME.textSecondary, whiteSpace: 'nowrap', flexShrink: 0 }}
        >
          {t('clarification.questionCount', { current: stepIndex + 1, total })}
        </span>
        {toggleButton}
      </div>
      ) : (
      <>
      <div style={{ borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`, flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 16px 8px' }}>
          <span style={{
            fontSize: 16, fontWeight: 600, flex: 1, minWidth: 0,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>
            {heading}
          </span>
          <Button
            type="text"
            size="small"
            disabled={submitting}
            data-testid={`elicitation-skip-${requestId}`}
            onClick={() => onReply({ action: 'decline' })}
            style={{ fontSize: 14, color: CLARIFICATION_THEME.textMuted }}
          >
            {t('clarification.skip')}
          </Button>
          {toggleButton}
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
                  title={t('clarification.questionTitle', { number: i + 1, label: f.label })}
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
            {t('clarification.questionCount', { current: stepIndex + 1, total })}
          </span>
        </div>
      </div>

      <div
        className={collapsible ? 'aw-elicitation-body' : undefined}
        style={{ padding: 16, lineHeight: 1.7, overflow: 'auto', flex: 1, minHeight: 0 }}
      >
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
          error={fieldErrors[field.name] ? validateElicitation([field], values, otherText).errors[field.name] : undefined}
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
          {t('clarification.previousQuestion')}
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
            {t('clarification.submit')}
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
            {t('clarification.nextQuestion')}
          </Button>
        )}
      </div>
      </>
      )}
    </div>
  );
}
