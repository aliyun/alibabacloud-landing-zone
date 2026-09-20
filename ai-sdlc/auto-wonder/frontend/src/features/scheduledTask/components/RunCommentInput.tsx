import { useEffect, useMemo, useRef, useState, type CSSProperties, type ChangeEvent, type KeyboardEvent } from 'react';
import { Button, Input, Space } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { useScheduledTaskRunMentionCandidates } from '../hooks';
import type { ScheduledRunCommentBody, ScheduledRunMentionCandidate } from '../types';

interface RunCommentInputProps {
  runId?: number;
  onSubmit: (payload: ScheduledRunCommentBody) => void;
  loading?: boolean;
}

const DEFAULT_DISABLED_REASON = '不在本次运行的冻结快照中，无法 @ 触发执行';

const mentionOptionHighlightStyle: CSSProperties = {
  color: '#0958d9',
  background: '#e6f4ff',
  borderRadius: 4,
  padding: '0 4px',
  fontWeight: 600,
};

const composerTextStyle: CSSProperties = {
  fontFamily: 'inherit',
  fontSize: 14,
  lineHeight: '22px',
  letterSpacing: 0,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'break-word',
};

const mentionMenuStyle: CSSProperties = {
  position: 'absolute',
  left: 0,
  right: 0,
  bottom: '100%',
  marginBottom: 6,
  padding: '4px 0',
  background: '#fff',
  border: '1px solid #d9d9d9',
  borderRadius: 6,
  boxShadow: '0 6px 16px rgba(0, 0, 0, 0.08)',
  zIndex: 20,
  maxHeight: 350,
  overflowY: 'auto',
};

const mentionMenuItemStyle: CSSProperties = {
  display: 'block',
  width: '100%',
  padding: '6px 12px',
  border: 0,
  background: 'transparent',
  textAlign: 'left',
  cursor: 'pointer',
};

const activeMentionMenuItemStyle: CSSProperties = {
  background: '#f0f7ff',
};

const disabledMentionMenuItemStyle: CSSProperties = {
  color: '#bfbfbf',
  cursor: 'not-allowed',
};

function findActiveMentionQuery(value: string, caretIndex: number): string | null {
  const textBeforeCaret = value.slice(0, caretIndex);
  const mentionStart = textBeforeCaret.lastIndexOf('@');

  if (mentionStart < 0) return null;

  const query = textBeforeCaret.slice(mentionStart + 1);
  if (/\s/.test(query)) return null;

  return query;
}

function targetTypeOf(candidate: ScheduledRunMentionCandidate): 'AGENT' | 'HUMAN' {
  const explicit = candidate.targetType?.toUpperCase();
  if (explicit === 'AGENT' || explicit === 'HUMAN') return explicit;
  return candidate.isAgent ? 'AGENT' : 'HUMAN';
}

export function RunCommentInput({ runId, onSubmit, loading }: RunCommentInputProps) {
  const [content, setContent] = useState('');
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [activeMentionIndex, setActiveMentionIndex] = useState(0);
  const [selectedMentionNames, setSelectedMentionNames] = useState<Map<string, string>>(new Map());
  const [targetAgentIds, setTargetAgentIds] = useState<number[]>([]);
  const [targetHumanIds, setTargetHumanIds] = useState<number[]>([]);
  const textAreaRef = useRef<TextAreaRef>(null);

  const { data: candidateData } = useScheduledTaskRunMentionCandidates(runId, mentionQuery, mentionQuery !== null);
  const candidates = useMemo(() => candidateData ?? [], [candidateData]);

  const candidateNameByTarget = useMemo(() => {
    const names = new Map<string, string>();
    candidates.forEach((candidate) => {
      if (typeof candidate.name === 'string' && candidate.name.trim() !== '') {
        names.set(`${targetTypeOf(candidate)}:${Number(candidate.userId)}`, candidate.name);
      }
    });
    return names;
  }, [candidates]);

  const mentionOptions = useMemo(() => {
    const normalizedQuery = mentionQuery?.toLowerCase() ?? '';

    return candidates
      .filter((candidate) => typeof candidate.name === 'string' && candidate.name.trim() !== '')
      .filter((candidate) => Number.isFinite(Number(candidate.userId)))
      .filter((candidate) => candidate.name!.toLowerCase().includes(normalizedQuery)
        || String(candidate.displayId ?? candidate.userId).toLowerCase().includes(normalizedQuery))
      .map((candidate) => {
        const targetType = targetTypeOf(candidate);
        const targetId = Number(candidate.userId);
        const disabled = candidate.mentionable === false;
        const disabledReason = disabled
          ? (candidate.mentionDisabledReason || DEFAULT_DISABLED_REASON)
          : null;
        return {
          key: `${targetType}:${targetId}`,
          value: candidate.name!,
          targetId,
          targetType,
          disabled,
          disabledReason,
          label: disabled ? (
            <span style={{ color: '#bfbfbf' }}>
              <span>@{candidate.name}</span>
              <span style={{ marginLeft: 6 }}>{targetType === 'AGENT' ? '数字人' : '真人'}</span>
              <span style={{ marginLeft: 6 }}>（{disabledReason}）</span>
            </span>
          ) : (
            <span>
              <span style={mentionOptionHighlightStyle}>@{candidate.name}</span>
              <span style={{ marginLeft: 6, color: targetType === 'AGENT' ? '#fa8c16' : '#1677ff' }}>
                {targetType === 'AGENT' ? '数字人' : '真人'}
              </span>
              {candidate.displayId ? <span style={{ marginLeft: 6, color: '#8c8c8c' }}>{candidate.displayId}</span> : null}
              {targetType === 'AGENT' && candidate.online === false ? '（离线，稍后送达）' : ''}
            </span>
          ),
        };
      });
  }, [candidates, mentionQuery]);

  const enabledOptions = useMemo(() => mentionOptions.filter((option) => !option.disabled), [mentionOptions]);
  const activeOption = enabledOptions[activeMentionIndex];

  useEffect(() => {
    setActiveMentionIndex(0);
  }, [mentionQuery]);

  useEffect(() => {
    setActiveMentionIndex((current) => {
      if (enabledOptions.length === 0) return 0;
      return Math.min(current, enabledOptions.length - 1);
    });
  }, [enabledOptions.length]);

  const hasMentionText = (value: string, targetType: 'AGENT' | 'HUMAN', targetId: number) => {
    const name = selectedMentionNames.get(`${targetType}:${targetId}`)
      ?? candidateNameByTarget.get(`${targetType}:${targetId}`);
    return name != null && value.includes(`@${name}`);
  };

  const syncSelectedMentions = (value: string) => {
    setTargetAgentIds((current) => current.filter((agentId) => hasMentionText(value, 'AGENT', agentId)));
    setTargetHumanIds((current) => current.filter((humanId) => hasMentionText(value, 'HUMAN', humanId)));
  };

  const resetMentionState = () => {
    setMentionQuery(null);
    setActiveMentionIndex(0);
    setSelectedMentionNames(new Map());
    setTargetAgentIds([]);
    setTargetHumanIds([]);
  };

  const handleSend = () => {
    const trimmed = content.trim();
    if (!trimmed) return;

    onSubmit({ contentMd: trimmed, targetAgentIds, targetHumanIds });
    setContent('');
    resetMentionState();
  };

  const handleChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
    const nextValue = event.target.value;
    const caretIndex = event.target.selectionStart ?? nextValue.length;

    setContent(nextValue);
    syncSelectedMentions(nextValue);
    setMentionQuery(findActiveMentionQuery(nextValue, caretIndex));
  };

  const insertMention = (option: { value: string; targetType: 'AGENT' | 'HUMAN'; targetId: number }) => {
    const textarea = textAreaRef.current?.resizableTextArea?.textArea;
    const selectionStart = textarea?.selectionStart ?? content.length;
    const selectionEnd = textarea?.selectionEnd ?? selectionStart;
    const textBeforeSelection = content.slice(0, selectionStart);
    const mentionStart = textBeforeSelection.lastIndexOf('@');
    const replaceStart = mentionStart >= 0 ? mentionStart : selectionStart;
    const mentionText = `@${option.value} `;
    const nextValue = `${content.slice(0, replaceStart)}${mentionText}${content.slice(selectionEnd)}`;
    const nextCaret = replaceStart + mentionText.length;

    setContent(nextValue);
    setMentionQuery(null);
    setActiveMentionIndex(0);
    setSelectedMentionNames((current) => {
      const next = new Map(current);
      next.set(`${option.targetType}:${option.targetId}`, option.value);
      return next;
    });
    syncSelectedMentions(nextValue);
    if (option.targetType === 'AGENT') {
      setTargetAgentIds((current) => current.includes(option.targetId) ? current : [...current, option.targetId]);
    } else {
      setTargetHumanIds((current) => current.includes(option.targetId) ? current : [...current, option.targetId]);
    }

    window.setTimeout(() => {
      textarea?.focus();
      textarea?.setSelectionRange(nextCaret, nextCaret);
    }, 0);
  };

  const moveActiveMention = (delta: number) => {
    if (enabledOptions.length === 0) return;
    setActiveMentionIndex((current) => (current + delta + enabledOptions.length) % enabledOptions.length);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.nativeEvent.isComposing) return;

    if (mentionQuery !== null && mentionOptions.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        moveActiveMention(1);
        return;
      }

      if (event.key === 'ArrowUp') {
        event.preventDefault();
        moveActiveMention(-1);
        return;
      }

      if (event.key === 'Escape') {
        event.preventDefault();
        setMentionQuery(null);
        setActiveMentionIndex(0);
        return;
      }

      if (event.key === 'Enter' && !event.shiftKey && activeOption) {
        event.preventDefault();
        insertMention(activeOption);
        return;
      }
    }

    if (event.key !== 'Enter' || event.shiftKey) return;

    event.preventDefault();
    handleSend();
  };

  const handleKeyUp = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (['ArrowDown', 'ArrowUp', 'Enter', 'Escape'].includes(event.key)) return;

    setMentionQuery(findActiveMentionQuery(content, event.currentTarget.selectionStart ?? content.length));
  };

  return (
    <Space.Compact style={{ width: '100%', alignItems: 'stretch' }} data-testid="run-comment-input">
      <div style={{ flex: 1, minWidth: 0, position: 'relative' }}>
        {mentionQuery !== null && mentionOptions.length > 0 && (
          <div role="menu" style={mentionMenuStyle}>
            {mentionOptions.map((option) => {
              const active = !option.disabled && option === activeOption;
              return (
                <button
                  key={option.key}
                  type="button"
                  role="menuitem"
                  aria-current={active ? 'true' : undefined}
                  aria-disabled={option.disabled ? 'true' : undefined}
                  disabled={option.disabled}
                  style={{
                    ...mentionMenuItemStyle,
                    ...(option.disabled ? disabledMentionMenuItemStyle : {}),
                    ...(active ? activeMentionMenuItemStyle : {}),
                  }}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => { if (!option.disabled) insertMention(option); }}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        )}
        <Input.TextArea
          ref={textAreaRef}
          aria-label="评论本次运行"
          value={content}
          onChange={handleChange}
          onClick={(event) => setMentionQuery(findActiveMentionQuery(content, event.currentTarget.selectionStart ?? content.length))}
          onKeyDown={handleKeyDown}
          onKeyUp={handleKeyUp}
          autoSize={{ minRows: 1, maxRows: 6 }}
          placeholder="评论本次运行，键入 @ 选择成员..."
          disabled={loading}
          style={{ width: '100%', ...composerTextStyle }}
        />
      </div>
      <Button
        aria-label="发送"
        type="primary"
        style={{ alignSelf: 'stretch', height: 'auto' }}
        loading={loading}
        onClick={handleSend}
      >
        发送
      </Button>
    </Space.Compact>
  );
}
