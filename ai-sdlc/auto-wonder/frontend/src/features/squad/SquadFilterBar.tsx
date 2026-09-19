import { useMemo } from 'react';
import { Segmented, Select, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { listAllSquads } from './api';
import { buildSquadNameById, type SquadOption } from './squadGrouping';

export function useSquadOptions() {
  // Walks every page: a single wide page would silently hide the squads past the cap from the
  // dropdown and from the grouped view. The queryKey prefix stays ['squads', 'filter-options']
  // because the agent / SDLC / squad pages invalidate it after squad CRUD.
  const { data, isLoading } = useQuery({
    queryKey: ['squads', 'filter-options'],
    queryFn: () => listAllSquads(),
    staleTime: 60_000,
  });

  const options = useMemo<SquadOption[]>(
    () => (data ?? []).map((squad) => ({ value: squad.id, label: squad.name })),
    [data],
  );
  const nameById = useMemo(() => buildSquadNameById(options), [options]);

  return { options, nameById, isLoading };
}

export interface SquadFilterBarProps {
  options: SquadOption[];
  value: number[];
  onChange: (next: number[]) => void;
  grouped: boolean;
  onGroupedChange: (next: boolean) => void;
  loading?: boolean;
  listLabel?: string;
  groupLabel?: string;
}

export function SquadFilterBar({
  options, value, onChange, grouped, onGroupedChange, loading,
  listLabel = '列表', groupLabel = '分组',
}: SquadFilterBarProps) {
  return (
    <Space wrap>
      <Select
        mode="multiple"
        allowClear
        showSearch
        optionFilterProp="label"
        placeholder="按小队筛选"
        style={{ minWidth: 220 }}
        loading={loading}
        value={value}
        onChange={onChange}
        options={options}
        maxTagCount="responsive"
        aria-label="按小队筛选"
      />
      <Segmented
        value={grouped ? 'group' : 'list'}
        onChange={(next) => onGroupedChange(next === 'group')}
        options={[
          { value: 'group', label: groupLabel },
          { value: 'list', label: listLabel },
        ]}
        aria-label="切换分组视图"
      />
    </Space>
  );
}
