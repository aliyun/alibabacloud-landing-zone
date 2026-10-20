import { useTranslation } from 'react-i18next';
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Select, Space, Typography } from 'antd';
import { listAllSquads, listSquads, getSquadMembers } from '@/features/squad/api';
import type { SquadMember } from '@/features/squad/api';

export interface SquadAgentSelection {
  squadId: number;
  agentId: number;
}

/**
 * 数字人当前所属小队（URL 刷新恢复用，工单 56281）：恢复身份必须与候选范围
 * 同口径——解析不出来的数字人本来也选不出来，此时调用方不该恢复它。
 * 小队列表走全量分页，避免超过单页上限后把真实归属漏判成「不在任何小队」。
 */
export async function findSquadIdForAgent(agentId: number): Promise<number | null> {
  const squads = await listAllSquads();
  const membersBySquad = await Promise.all(squads.map((squad) => getSquadMembers(squad.id)));
  const index = membersBySquad.findIndex(
    (members) => members.some((member) => member.agentId === agentId),
  );
  return index < 0 ? null : squads[index].id;
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
  const { t } = useTranslation('workitem');
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
          {t('clarification.squad')}
        </Typography.Text>
        <Select
          style={{ width: '100%' }}
          placeholder={t('clarification.selectSquad')}
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
          {t('clarification.agent')}
        </Typography.Text>
        <Select
          style={{ width: '100%' }}
          placeholder={squadId ? t('clarification.selectAgent') : t('clarification.selectSquadFirst')}
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
