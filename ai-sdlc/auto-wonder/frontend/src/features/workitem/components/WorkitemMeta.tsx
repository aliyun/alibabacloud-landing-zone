import { Tag } from 'antd';
import { getPriorityMeta } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';

interface WorkitemMetaProps {
  priority: number;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeType: string;
  creatorDisplayName?: string | null;
  sdlcName: string | null;
  tags?: string[];
}

export function WorkitemMeta({ priority, assigneeName, assigneeDisplayName, assigneeType, creatorDisplayName, sdlcName, tags }: WorkitemMetaProps) {
  const priorityMeta = getPriorityMeta(priority);
  const assigneeText = displayNameWithoutId(assigneeDisplayName, assigneeName);
  const isAgent = assigneeType === 'AGENT';

  return (
    <div style={{ marginTop: 14, fontSize: 12, color: '#666', display: 'flex', gap: 16, flexWrap: 'wrap', lineHeight: 1.7 }}>
      <span>
        {'优先级: '}
        <span style={{ color: priorityMeta.color }}>{priorityMeta.label}</span>
      </span>
      {creatorDisplayName && (
        <span>{'创建者: '}{displayNameWithoutId(creatorDisplayName)}</span>
      )}
      <span>
        {'当前处理人: '}
        {isAgent && <Tag color="purple" style={{ margin: 0 }}>AI</Tag>}
        {isAgent ? ' ' : ''}
        {assigneeText ?? '未指派'}
      </span>
      {sdlcName && (
        <span>SDLC: {sdlcName}</span>
      )}
      {tags && tags.length > 0 && (
        <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center', flexWrap: 'wrap' }}>
          标签: {tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
        </span>
      )}
    </div>
  );
}
