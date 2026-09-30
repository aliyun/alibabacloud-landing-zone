import { Typography } from 'antd';
import type { AcpSlashCommand } from './types';
import { CLARIFICATION_THEME } from './theme';

interface SlashCommandPickerProps {
  commands: AcpSlashCommand[];
  /** null 表示当前不在补全态；空串表示刚敲下 `/`，列出全部候选 */
  query: string | null;
  onSelect: (command: AcpSlashCommand) => void;
}

/** 只有整段输入是一个还没敲完的斜杠 token 时才补全：敲了空白说明命令已选定，
 *  后面是参数，此时继续弹浮层会挡住正在写的内容。 */
export function slashCommandQuery(input: string): string | null {
  if (!input.startsWith('/')) return null;
  const rest = input.slice(1);
  return /\s/.test(rest) ? null : rest;
}

export function filterSlashCommands(
  commands: AcpSlashCommand[],
  query: string,
): AcpSlashCommand[] {
  const needle = query.toLowerCase();
  return commands.filter((command) => command.name.toLowerCase().startsWith(needle));
}

export function SlashCommandPicker({ commands, query, onSelect }: SlashCommandPickerProps) {
  if (query == null) return null;
  const matched = filterSlashCommands(commands, query);
  if (matched.length === 0) return null;

  return (
    <div
      data-testid="slash-command-picker"
      style={{
        position: 'absolute', left: 0, right: 0, bottom: '100%', marginBottom: 4,
        maxHeight: 220, overflow: 'auto',
        backgroundColor: CLARIFICATION_THEME.surface,
        border: `1px solid ${CLARIFICATION_THEME.controlBorder}`,
        borderRadius: CLARIFICATION_THEME.radiusControl, zIndex: 10,
        boxShadow: '0 2px 8px rgba(0, 0, 0, 0.15)',
      }}
    >
      {matched.map((command) => (
        <div
          key={command.name}
          role="button"
          tabIndex={0}
          data-testid={`slash-command-${command.name}`}
          onClick={() => onSelect(command)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') onSelect(command);
          }}
          style={{
            padding: '6px 10px', cursor: 'pointer',
            borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <Typography.Text strong style={{ fontSize: 14 }}>{`/${command.name}`}</Typography.Text>
            {command.input?.hint ? (
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                {command.input.hint}
              </Typography.Text>
            ) : null}
          </div>
          {command.description ? (
            <div style={{ fontSize: 13, color: CLARIFICATION_THEME.textMuted }}>
              {command.description}
            </div>
          ) : null}
        </div>
      ))}
    </div>
  );
}
