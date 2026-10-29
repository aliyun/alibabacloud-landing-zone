import { useState } from 'react';
import { Alert, Button, Skeleton } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs, { type Dayjs } from 'dayjs';
import { formatNumber } from '@/shared/i18n/format';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { useSwitchWorkspace } from '@/features/auth/useSwitchWorkspace';
import { useInsightDateRange } from '@/features/insights/useInsightPreference';
import { useSpaceDashboardOverview } from './hooks';
import type { SpaceDashboardCompletedRank, SpaceDashboardCreditsRank } from './types';
import { DashboardSection } from './components/DashboardSection';
import { StatCard } from './components/StatCard';
import { WorkspaceRankCard, type RankEntry } from './components/WorkspaceRankCard';
import { TimeWindowFilter } from './components/TimeWindowFilter';
import { WorkTypeFilter, DASHBOARD_WORK_TYPES, type DashboardWorkType } from './components/WorkTypeFilter';
import { SpaceDashboardForbidden } from './components/SpaceDashboardForbidden';
import './spaceDashboard.css';

/** 默认近 30 天且截止到「昨天」，与 insights 页 dataThrough 语义一致。 */
function defaultDateRange(): [Dayjs, Dayjs] {
  const end = dayjs().subtract(1, 'day');
  return [end.subtract(29, 'day'), end];
}

function formatInteger(value: number): string {
  return formatNumber(value);
}

function formatCredits(value: number): string {
  return formatNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function completedEntries(items: SpaceDashboardCompletedRank[]): RankEntry[] {
  return items.map((item) => ({
    id: item.workspaceId,
    name: item.workspaceName,
    value: formatInteger(item.value),
  }));
}

function creditsEntries(items: SpaceDashboardCreditsRank[]): RankEntry[] {
  return items.map((item) => ({
    id: item.workspaceId,
    name: item.workspaceName,
    value: formatCredits(item.value),
  }));
}

/**
 * 空间级看板：数字员工、工单交付、成本分析三个版块共用一次聚合请求。
 * 统计范围由服务端按调用者可管理的空间集合决定，前端只负责展示与下钻。
 */
export function SpaceDashboardPage() {
  const { t } = useTranslation('spaceDashboard');
  const switchWorkspace = useSwitchWorkspace();

  // 两个版块各用一个偏好键，窗口互相独立；最长 181 天由 hook 与后端共同保证。
  const [deliveryRange, setDeliveryRange] = useInsightDateRange(
    'spaceDashboard.delivery.dates', defaultDateRange, 181, dayjs(), dayjs().subtract(180, 'day'));
  const [costRange, setCostRange] = useInsightDateRange(
    'spaceDashboard.cost.dates', defaultDateRange, 181, dayjs(), dayjs().subtract(180, 'day'));
  const [workTypes, setWorkTypes] = useState<DashboardWorkType[]>([...DASHBOARD_WORK_TYPES]);

  const deliveryStart = deliveryRange[0].format('YYYY-MM-DD');
  const deliveryEnd = deliveryRange[1].format('YYYY-MM-DD');
  const costStart = costRange[0].format('YYYY-MM-DD');
  const costEnd = costRange[1].format('YYYY-MM-DD');

  const { data, isLoading, isError, isFetching, error, refetch } = useSpaceDashboardOverview({
    deliveryStart,
    deliveryEnd,
    workTypes,
    costStart,
    costEnd,
  });

  const handleOpenWorkspace = async (workspaceId: number) => {
    const workspace = data?.workspaces.find((item) => item.id === workspaceId);
    if (!workspace) {
      return;
    }
    await switchWorkspace({ id: workspace.id, name: workspace.name, description: '' }, '/insights');
  };

  if (isLoading) {
    return (
      <div className="space-dashboard-page" data-testid="space-dashboard-loading">
        <Skeleton active paragraph={{ rows: 2 }} />
        <Skeleton active paragraph={{ rows: 4 }} />
      </div>
    );
  }

  const forbidden = error instanceof ApiError && error.code === ErrorCodes.NO_PERMISSION;

  if (forbidden) {
    return (
      <div className="space-dashboard-page">
        <SpaceDashboardForbidden />
      </div>
    );
  }

  if (isError || data == null) {
    return (
      <div className="space-dashboard-page">
        <Alert
          type="error"
          showIcon
          message={t('state.error')}
          data-testid="space-dashboard-error"
          action={
            <Button size="small" onClick={() => { void refetch(); }} loading={isFetching}
              data-testid="space-dashboard-retry">
              {t('state.retry')}
            </Button>
          }
        />
      </div>
    );
  }

  const deliveryDays = deliveryRange[1].diff(deliveryRange[0], 'day') + 1;
  const costDays = costRange[1].diff(costRange[0], 'day') + 1;

  return (
    <div className="space-dashboard-page" aria-busy={isFetching} data-testid="space-dashboard-page">
      <div className="space-dashboard-scope" data-testid="space-dashboard-scope">
        <InfoCircleOutlined className="space-dashboard-scope-icon" aria-hidden="true" />
        <div>
          <strong>
            {data.platformAdmin
              ? t('scope.platform', { total: formatInteger(data.workspaceCount) })
              : t('scope.managed', { total: formatInteger(data.workspaceCount) })}
          </strong>
          <span className="space-dashboard-scope-detail">
            {data.platformAdmin ? t('scope.platformDetail') : t('scope.managedDetail')}
          </span>
        </div>
      </div>

      <DashboardSection title={t('section.agents')} subtitle={t('section.agentsSubtitle')}>
        <div className="space-dashboard-grid">
          <StatCard testId="stat-workspace-count" label={t('agents.workspaceCount')}
            value={formatInteger(data.agents.workspaceCount)} hint={t('agents.workspaceHint')} />
          <StatCard testId="stat-agent-count" label={t('agents.agentCount')}
            value={formatInteger(data.agents.agentCount)} hint={t('agents.agentHint')}
            tooltip={t('agents.agentTooltip')} />
          <StatCard testId="stat-human-count" label={t('agents.humanCount')}
            value={formatInteger(data.agents.humanCount)} hint={t('agents.humanHint')}
            tooltip={t('agents.humanTooltip')} />
          <StatCard testId="stat-squad-count" label={t('agents.squadCount')}
            value={formatInteger(data.agents.squadCount)} hint={t('agents.squadHint')} />
        </div>
      </DashboardSection>

      <DashboardSection
        title={t('section.delivery')}
        toolbar={
          <>
            <TimeWindowFilter ariaLabel={t('range.delivery')} value={deliveryRange} onChange={setDeliveryRange} />
            <WorkTypeFilter value={workTypes} onChange={setWorkTypes} />
          </>
        }
      >
        <div className="space-dashboard-grid">
          <StatCard testId="stat-workitem-count" label={t('delivery.workitem')}
            value={formatInteger(data.delivery.workitemCount)}
            hint={t('delivery.workitemHint', { days: deliveryDays })}
            tooltip={t('delivery.workitemTooltip')} />
          <StatCard testId="stat-completed-count" label={t('delivery.completed')}
            value={formatInteger(data.delivery.completedCount)}
            hint={t('delivery.completedHint', { days: deliveryDays })}
            tooltip={t('delivery.completedTooltip')} />
          <StatCard testId="stat-in-progress-count" label={t('delivery.inProgress')}
            value={formatInteger(data.delivery.inProgressCount)}
            hint={t('delivery.inProgressHint')}
            tooltip={t('delivery.inProgressTooltip')} />
          <WorkspaceRankCard testId="rank-top-completed" title={t('delivery.topCompleted')}
            entries={completedEntries(data.delivery.topCompleted)} total={data.delivery.topCompletedTotal}
            emptyText={t('rank.empty')} hint={t('delivery.rankHint')}
            onSelect={(id) => { void handleOpenWorkspace(id); }} />
          <WorkspaceRankCard testId="rank-bottom-completed" title={t('delivery.bottomCompleted')}
            entries={completedEntries(data.delivery.bottomCompleted)} total={data.delivery.bottomCompletedTotal}
            emptyText={t('rank.empty')} hint={t('delivery.rankZeroHint')}
            onSelect={(id) => { void handleOpenWorkspace(id); }} />
        </div>
      </DashboardSection>

      <DashboardSection
        title={t('section.cost')}
        toolbar={
          <TimeWindowFilter ariaLabel={t('range.cost')} value={costRange} onChange={setCostRange} />
        }
      >
        <div className="space-dashboard-grid">
          <StatCard testId="stat-total-credits" label={t('cost.total')}
            value={formatCredits(data.cost.totalCredits)}
            hint={t('cost.totalHint', { days: costDays })}
            tooltip={t('cost.totalTooltip')} />
          <StatCard testId="stat-daily-average" label={t('cost.dailyAverage')}
            value={formatCredits(data.cost.dailyAverage)}
            hint={t('cost.dailyHint', { days: costDays })}
            tooltip={t('cost.dailyTooltip')} />
          <StatCard testId="stat-per-workitem" label={t('cost.perWorkitem')}
            value={data.cost.perCompletedWorkitem == null
              ? t('cost.unavailableValue')
              : formatCredits(data.cost.perCompletedWorkitem)}
            hint={data.cost.perCompletedWorkitem == null
              ? t('cost.perWorkitemUnavailableHint')
              : t('cost.perWorkitemHint', { done: formatInteger(data.cost.completedWorkitemCount) })}
            tooltip={t('cost.perWorkitemTooltip')} />
          <WorkspaceRankCard testId="rank-top-credits" title={t('cost.topCredits')}
            entries={creditsEntries(data.cost.topCredits)} total={data.cost.topCreditsTotal}
            emptyText={t('rank.empty')} hint={t('cost.rankHint')}
            onSelect={(id) => { void handleOpenWorkspace(id); }} />
          <WorkspaceRankCard testId="rank-bottom-credits" title={t('cost.bottomCredits')}
            entries={creditsEntries(data.cost.bottomCredits)} total={data.cost.bottomCreditsTotal}
            emptyText={t('rank.empty')} hint={t('cost.rankZeroHint')}
            onSelect={(id) => { void handleOpenWorkspace(id); }} />
        </div>
      </DashboardSection>
    </div>
  );
}
