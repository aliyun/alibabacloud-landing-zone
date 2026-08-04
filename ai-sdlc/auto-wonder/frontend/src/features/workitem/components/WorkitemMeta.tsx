interface WorkitemMetaProps {
  priority: number;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeType: string;
  creatorDisplayName?: string | null;
  sdlcName: string | null;
}

export function WorkitemMeta({ priority, assigneeName, assigneeDisplayName, assigneeType, creatorDisplayName, sdlcName }: WorkitemMetaProps) {
  const assigneeLabel = assigneeType === 'AGENT' ? '数字员工' : '人工';
  const assigneeText = assigneeDisplayName || assigneeName;

  return (
    <div style={{ marginTop: 14, fontSize: 12, color: '#666', display: 'flex', gap: 16, flexWrap: 'wrap', lineHeight: 1.7 }}>
      <span>优先级: P{priority}</span>
      {creatorDisplayName && (
        <span>创建者: {creatorDisplayName}</span>
      )}
      {assigneeText && (
        <span>指派: {assigneeText} ({assigneeLabel})</span>
      )}
      {sdlcName && (
        <span>SDLC: {sdlcName}</span>
      )}
    </div>
  );
}
