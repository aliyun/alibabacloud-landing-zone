import type { CSSProperties } from 'react';

/** 澄清面板复用全站外观令牌，保留平铺回复与气泡尺度。 */
export const CLARIFICATION_THEME = {
  surface: 'var(--aw-panel)',
  hairline: 'var(--aw-border)',
  controlBorder: 'var(--aw-border)',
  userBubble: 'var(--aw-raised)',
  codeSurface: 'var(--aw-raised)',
  codeBorder: 'var(--aw-border)',
  textPrimary: 'var(--aw-text)',
  textSecondary: 'var(--aw-muted)',
  textMuted: 'var(--aw-muted)',
  radiusBubble: 14,
  radiusBlock: 8,
  radiusControl: 12,
} as const;

/** 用户消息气泡：浅灰底、无边框、大圆角，靠右。
 *  不设 whiteSpace——内容经 MarkdownView 渲染，其内部按 markdown 语义控制。 */
export function userBubbleStyle(): CSSProperties {
  return {
    padding: '10px 14px',
    borderRadius: CLARIFICATION_THEME.radiusBubble,
    backgroundColor: CLARIFICATION_THEME.userBubble,
    fontSize: 16,
    lineHeight: '1.7',
  };
}

/** AI 回复：平铺在白底上，无底色、无边框、无内距。
 *  流式态与完成态共用本函数，保证回复过程中与完成后渲染一致。 */
export function agentBlockStyle(): CSSProperties {
  return {
    fontSize: 16,
    lineHeight: '1.75',
    color: CLARIFICATION_THEME.textPrimary,
  };
}
