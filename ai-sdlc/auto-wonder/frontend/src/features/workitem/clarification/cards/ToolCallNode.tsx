import {
  CodeOutlined, FileTextOutlined, EditOutlined, DeleteOutlined,
  SwapOutlined, SearchOutlined, BulbOutlined, CloudDownloadOutlined, ToolOutlined,
} from '@ant-design/icons';
import { Tag, Typography } from 'antd';
import type { ReactElement } from 'react';
import type { AcpToolKind } from '../types';
import type { TimelineToolCall } from '../timeline';
import { CLARIFICATION_THEME } from '../theme';

interface ToolCallNodeProps {
  tool: TimelineToolCall;
}

const KIND_ICON: Record<AcpToolKind, ReactElement> = {
  execute: <CodeOutlined data-testid="tool-icon-execute" />,
  read: <FileTextOutlined data-testid="tool-icon-read" />,
  edit: <EditOutlined data-testid="tool-icon-edit" />,
  delete: <DeleteOutlined data-testid="tool-icon-delete" />,
  move: <SwapOutlined data-testid="tool-icon-move" />,
  search: <SearchOutlined data-testid="tool-icon-search" />,
  think: <BulbOutlined data-testid="tool-icon-think" />,
  fetch: <CloudDownloadOutlined data-testid="tool-icon-fetch" />,
  other: <ToolOutlined data-testid="tool-icon-other" />,
};

const PRE_STYLE = {
  fontSize: 13, whiteSpace: 'pre-wrap' as const, maxHeight: 150, overflow: 'auto',
  padding: '6px 10px', color: CLARIFICATION_THEME.textSecondary,
  backgroundColor: CLARIFICATION_THEME.codeSurface,
  border: `1px solid ${CLARIFICATION_THEME.codeBorder}`,
  borderRadius: CLARIFICATION_THEME.radiusBlock, margin: '2px 0 0',
};

const SUMMARY_STYLE = {
  cursor: 'pointer' as const, fontSize: 13, color: CLARIFICATION_THEME.textMuted,
};

export function ToolCallNode({ tool }: ToolCallNodeProps) {
  const icon = KIND_ICON[tool.toolKind ?? 'other'] ?? KIND_ICON.other;
  const diffs = tool.diffs ?? [];

  return (
    <div data-testid={`tool-call-${tool.callId}`} style={{ marginBottom: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {icon}
        <Typography.Text
          data-testid="tool-call-title"
          title={tool.title || tool.tool}
          style={{
            flex: 1,
            minWidth: 0,
            fontSize: 14,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {tool.title || tool.tool}
        </Typography.Text>
        <Tag color={statusColor(tool.status)} style={{ marginInlineEnd: 0, fontSize: 13 }}>
          {tool.status}
        </Tag>
      </div>

      {tool.locations && tool.locations.length > 0 ? (
        <div
          data-testid="tool-locations"
          style={{ fontSize: 13, color: CLARIFICATION_THEME.textMuted, marginTop: 2 }}
        >
          {tool.locations
            .map((loc) => (loc.line != null ? `${loc.path}:${loc.line}` : loc.path))
            .join('、')}
        </div>
      ) : null}

      {diffs.length > 0 ? (
        <div data-testid="tool-diffs" style={{ marginTop: 4 }}>
          {diffs.map((diff, index) => (
            <div
              key={index}
              data-testid={`tool-diff-${index}`}
              style={{
                border: `1px solid ${CLARIFICATION_THEME.hairline}`,
                borderRadius: CLARIFICATION_THEME.radiusBlock, marginBottom: 4,
                overflow: 'hidden',
              }}
            >
              <div style={{
                fontSize: 13, padding: '2px 8px',
                backgroundColor: CLARIFICATION_THEME.codeSurface,
                borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
                fontFamily: 'monospace',
              }}>
                {diff.path}
              </div>
              {/* 增删色是语义色，不走中性令牌 */}
              {diff.oldText ? (
                <pre
                  data-testid={`tool-diff-old-${index}`}
                  style={{
                    ...PRE_STYLE, backgroundColor: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))', color: 'var(--aw-error)',
                    border: 'none', borderRadius: 0, margin: 0,
                  }}
                >
                  {prefixLines(diff.oldText, '-')}
                </pre>
              ) : null}
              {diff.newText ? (
                <pre
                  data-testid={`tool-diff-new-${index}`}
                  style={{
                    ...PRE_STYLE, backgroundColor: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', color: 'var(--aw-success)',
                    border: 'none', borderRadius: 0, margin: 0,
                  }}
                >
                  {prefixLines(diff.newText, '+')}
                </pre>
              ) : null}
            </div>
          ))}
        </div>
      ) : null}

      {tool.terminalOutput ? (
        <pre
          data-testid="tool-terminal-output"
          style={{
            fontSize: 13, whiteSpace: 'pre-wrap', maxHeight: 200, overflow: 'auto',
            padding: '6px 10px', color: CLARIFICATION_THEME.textSecondary,
            backgroundColor: CLARIFICATION_THEME.codeSurface,
            border: `1px solid ${CLARIFICATION_THEME.codeBorder}`,
            borderRadius: CLARIFICATION_THEME.radiusBlock, marginTop: 4,
            fontFamily: 'monospace',
          }}
        >
          {tool.terminalOutput}
        </pre>
      ) : null}

      {tool.input ? (
        <details style={{ marginTop: 4 }}>
          <summary style={SUMMARY_STYLE}>输入</summary>
          <pre data-testid="tool-input" style={PRE_STYLE}>{tool.input}</pre>
        </details>
      ) : null}

      {tool.output ? (
        <details style={{ marginTop: 4 }}>
          <summary style={SUMMARY_STYLE}>输出</summary>
          <pre data-testid="tool-output" style={PRE_STYLE}>{tool.output}</pre>
        </details>
      ) : null}
    </div>
  );
}

function statusColor(status: string): string {
  if (status === 'completed') return 'green';
  if (status === 'failed') return 'red';
  return 'processing';
}

function prefixLines(text: string, prefix: string): string {
  return text.split('\n').map((line) => `${prefix} ${line}`).join('\n');
}
