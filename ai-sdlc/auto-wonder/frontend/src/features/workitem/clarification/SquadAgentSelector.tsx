import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Select, Space, Typography } from 'antd';
import { listSquads, getSquadMembers } from '@/features/squad/api';
import type { SquadMember } from '@/features/squad/api';

export interface SquadAgentSelection {
  squadId: number;
  agentId: number;
}

interface SquadAgentSelectorProps {
  value: SquadAgentSelection | null;
  onChange: (selection: SquadAgentSelection | null) => void;
  /** When true the squad picker is disabled (e.g. locked by delivery progress). */
  locked?: boolean;
}

/**
 * Two-level selector: squad first, then agent within that squad.
 *
 * Reuses the same data sources as StartDeliveryModal. Online status is not
 * enforced here — the clarification backend resolves the executor lazily via
 * ExecutorSelector when the conversation is created.
 */
export function SquadAgentSelector({ value, onChange, locked }: SquadAgentSelectorProps) {
  const { data: squads, isLoading: squadsLoading } = useQuery({
    queryKey: ['squads', 'clarification'],
    queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }),
  });

  const squadId = value?.squadId ?? null;
  const { data: members = [], isLoading: membersLoading } = useQuery({
    queryKey: ['squad-members', 'clarification', squadId],
    queryFn: () => getSquadMembers(squadId!),
    enabled: !!squadId,
  });

  const squadOptions = useMemo(
    () => (squads?.list ?? []).map((s) => ({ value: s.id, label: s.name })),
    [squads],
  );
  const agentOptions = useMemo(
    () =>
      members.map((m: SquadMember) => ({
        value: m.agentId,
        label: m.roleCode ? `${m.agentName} (${m.roleCode})` : m.agentName,
      })),
    [members],
  );

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={8}>
      <div>
        <Typography.Text strong style={{ display: 'block', marginBottom: 4 }}>
          小队
        </Typography.Text>
        <Select
          style={{ width: '100%' }}
          placeholder="选择小队"
          loading={squadsLoading}
          options={squadOptions}
          value={squadId}
          disabled={locked}
          onChange={(next) => {
            onChange(next == null ? null : { squadId: next, agentId: 0 });
          }}
        />
      </div>
      <div>
        <Typography.Text strong style={{ display: 'block', marginBottom: 4 }}>
          数字人
        </Typography.Text>
        <Select
          style={{ width: '100%' }}
          placeholder={squadId ? '选择数字人' : '请先选择小队'}
          disabled={!squadId || locked}
          loading={membersLoading}
          options={agentOptions}
          value={value?.agentId && value.agentId > 0 ? value.agentId : undefined}
          onChange={(next) => {
            if (!squadId || next == null) return;
            onChange({ squadId, agentId: next });
          }}
        />
      </div>
    </Space>
  );
}
