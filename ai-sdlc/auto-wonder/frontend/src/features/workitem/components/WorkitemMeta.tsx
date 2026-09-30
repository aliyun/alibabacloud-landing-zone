import { Tag } from 'antd';
import { getPriorityMeta, workTypeMap } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';

interface WorkitemMetaProps {
  workType: string;
  statusName: string | null;
  priority: number;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeType: string;
  creatorDisplayName?: string | null;
  sdlcName: string | null;
  tags?: string[];
}

export function WorkitemMeta({ workType, statusName, priority, assigneeName, assigneeDisplayName, assigneeType, creatorDisplayName, sdlcName, tags }: WorkitemMetaProps) {
  const priorityMeta = getPriorityMeta(priority);
  const assigneeText = displayNameWithoutId(assigneeDisplayName, assigneeName);
  const isAgent = assigneeType === 'AGENT';

  return (
    <div className="aw-workitem-meta">
      <span>
        {'工单类型: '}
        <Tag style={{ margin: 0 }}>{workTypeMap[workType]?.label ?? workType}</Tag>
      </span>
      <span>
        {'工单状态: '}
        <Tag style={{ margin: 0, color: 'var(--aw-accent-text)', borderColor: 'var(--aw-accent)', background: 'rgba(var(--aw-accent-rgb),.10)' }}>{statusName ?? '未知'}</Tag>
      </span>
      <span>
        {'优先级: '}
        <Tag color={priorityMeta.color} style={{ margin: 0 }}>{priorityMeta.label}</Tag>
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
        <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center', flexWrap: 'wrap', minWidth: 0 }}>
          标签: {tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
        </span>
      )}
    </div>
  );
}
