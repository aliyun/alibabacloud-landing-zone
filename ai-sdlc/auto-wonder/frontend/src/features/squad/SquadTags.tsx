import { Tag } from 'antd';
import { Link } from 'react-router-dom';
import { UNSQUADED_LABEL } from './squadGrouping';

export interface SquadTagsProps {
  squadIds?: readonly number[] | null;
  squadNames?: readonly string[] | null;
}

/** Renders every squad a resource belongs to; clicking one opens that squad's detail. */
export function SquadTags({ squadIds, squadNames }: SquadTagsProps) {
  const ids = squadIds ?? [];
  if (ids.length === 0) {
    return <Tag color="default">{UNSQUADED_LABEL}</Tag>;
  }
  return (
    // 行间距 4px：小队多时标签垂直换行，没有行距会上下贴死（工单/SDLC 列表复用）
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 4px' }}>
      {ids.map((squadId, index) => (
        <Tag key={squadId} color="blue" style={{ marginInlineEnd: 0 }}>
          <Link to={`/agents?tab=squads&squadId=${squadId}`}>{squadNames?.[index] ?? `小队 #${squadId}`}</Link>
        </Tag>
      ))}
    </div>
  );
}
