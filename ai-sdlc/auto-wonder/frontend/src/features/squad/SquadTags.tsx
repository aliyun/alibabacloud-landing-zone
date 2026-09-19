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
    <>
      {ids.map((squadId, index) => (
        <Tag key={squadId} color="blue" style={{ marginInlineEnd: 4 }}>
          <Link to={`/squads?squadId=${squadId}`}>{squadNames?.[index] ?? `小队 #${squadId}`}</Link>
        </Tag>
      ))}
    </>
  );
}
