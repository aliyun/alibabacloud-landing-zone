import { useEffect, useState } from 'react';

export type ReplyingIndicatorStyle = 'dots' | 'spinner' | 'cursor';

export const REPLYING_INDICATOR_STYLES: ReplyingIndicatorStyle[] = ['dots', 'spinner', 'cursor'];

export const CLARIFICATION_REPLYING_TEXTS: string[] = [
  'thinking…',
  '思考中…',
  '正在分析需求…',
  '正在整理思路…',
  '正在组织语言…',
  '正在查阅工单资料…',
  '正在核对验收标准…',
  '正在生成澄清问题…',
];

export const REPLYING_INDICATOR_ROTATE_INTERVAL_MS = 5000;

/** 从候选中随机挑一个不同于 current 的项；pick 返回 [0, n) 的整数，便于测试注入。 */
export function pickNextReplyingIndicator<T>(current: T, candidates: readonly T[],
  pick: (n: number) => number = defaultPick): T {
  if (candidates.length === 0) return current;
  if (candidates.length === 1) return candidates[0];
  const others = candidates.filter((c) => c !== current);
  return others[pick(others.length) % others.length];
}

function defaultPick(n: number): number {
  return Math.floor(Math.random() * n);
}

const replyIndicatorCss = `
@keyframes aw-clarify-dot-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-4px); opacity: 1; }
}
@keyframes aw-clarify-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
@keyframes aw-clarify-cursor-blink {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}
`;

interface ReplyingIndicatorProps {
  agentName?: string;
}

/** 模型未结束回复期间的动态状态行：三种样式 + 文案池，每 5 秒随机轮换且不连续重复。 */
export function ReplyingIndicator({ agentName }: ReplyingIndicatorProps) {
  const [style, setStyle] = useState<ReplyingIndicatorStyle>('dots');
  const [text, setText] = useState<string>(() =>
    CLARIFICATION_REPLYING_TEXTS[defaultPick(CLARIFICATION_REPLYING_TEXTS.length)]);

  useEffect(() => {
    const timer = setInterval(() => {
      setStyle((prev) => pickNextReplyingIndicator(prev, REPLYING_INDICATOR_STYLES));
      setText((prev) => pickNextReplyingIndicator(prev, CLARIFICATION_REPLYING_TEXTS));
    }, REPLYING_INDICATOR_ROTATE_INTERVAL_MS);
    return () => clearInterval(timer);
  }, []);

  return (
    <div
      data-testid="clarification-replying-indicator"
      style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '4px 0', fontSize: 12, color: '#8c8c8c' }}
    >
      <style>{replyIndicatorCss}</style>
      {style === 'dots' ? (
        <span style={{ display: 'inline-flex', gap: 3 }} aria-hidden>
          {[0, 1, 2].map((i) => (
            <span
              key={i}
              style={{
                width: 5, height: 5, borderRadius: '50%', backgroundColor: '#8c8c8c',
                animation: `aw-clarify-dot-bounce 1.2s infinite ${i * 0.2}s`,
              }}
            />
          ))}
        </span>
      ) : null}
      {style === 'spinner' ? (
        <span
          aria-hidden
          style={{
            width: 12, height: 12, borderRadius: '50%',
            border: '2px solid #d9d9d9', borderTopColor: '#8c8c8c',
            animation: 'aw-clarify-spin 0.8s linear infinite',
          }}
        />
      ) : null}
      <span>
        {agentName ? `${agentName} ` : ''}{text}{style === 'cursor' ? <span
          aria-hidden
          style={{ animation: 'aw-clarify-cursor-blink 1s step-end infinite' }}
        >▍</span> : null}
      </span>
    </div>
  );
}
