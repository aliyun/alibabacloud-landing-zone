import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { AuthLayout } from './AuthLayout';
import { AppLayout } from './AppLayout';
import { RouteGuard } from './RouteGuard';
import { RedirectWithQuery } from './RedirectWithQuery';
import { LoginPage } from '@/features/auth/LoginPage';
import { RegisterPage } from '@/features/auth/RegisterPage';
import { WorkspaceSelectPage } from '@/features/auth/WorkspaceSelectPage';
import { WorkspaceRecycleBinPage } from '@/features/auth/WorkspaceRecycleBinPage';
import { WorkitemListPage } from '@/features/workitem/WorkitemListPage';
import { WorkitemCreatePage } from '@/features/workitem/WorkitemCreatePage';
import { WorkitemDetailPage } from '@/features/workitem/WorkitemDetailPage';
import { AgentCreatePage } from '@/features/agent/AgentCreatePage';
import { AgentDetailPage } from '@/features/agent/AgentDetailPage';
import { AgentEditPage } from '@/features/agent/AgentEditPage';
import { AgentsHubPage } from '@/features/agent/AgentsHubPage';
import { ExecutorListPage } from '@/features/executor/ExecutorListPage';
import { ExecutionListPage } from '@/features/execution/ExecutionListPage';
import { SdlcListPage } from '@/features/sdlc/SdlcListPage';
import { SdlcGeneratePage } from '@/features/sdlc/SdlcGeneratePage';
import { SdlcDetailPage } from '@/features/sdlc/SdlcDetailPage';
import { ReposHubPage } from '@/features/repo/ReposHubPage';
import { RepoDetailPage } from '@/features/repo/RepoDetailPage';
import { MemoryListPage } from '@/features/memory/MemoryListPage';
import { NotificationCenterPage } from '@/features/notification/NotificationCenterPage';
import { SkillListPage } from '@/features/skill/SkillListPage';
import { AuditLogPage } from '@/features/audit/AuditLogPage';
import { ProjectBackupsPage } from '@/features/settings/ProjectBackupsPage';
import { SettingsPage } from '@/features/settings/SettingsPage';
import { MemberRoleSettingsPage } from '@/features/settings/MemberRoleSettingsPage';
import { StatusTemplatePage } from '@/features/statemachine/StatusTemplatePage';
import { InsightsPage } from '@/features/insights/InsightsPage';
import { IntegrationsHubPage } from '@/features/integration/IntegrationsHubPage';
import { AboutAutoWonderPage } from '@/features/about/AboutAutoWonderPage';
import { EvolutionPage } from '@/features/evolution/EvolutionPage';
import { BrandingConfigPage } from '@/features/platform/BrandingConfigPage';
import { ProfileSettingsPage } from '@/features/profile/ProfileSettingsPage';
import { ScheduledTaskListPage } from '@/features/scheduledTask/ScheduledTaskListPage';
import { ScheduledTaskCreatePage } from '@/features/scheduledTask/ScheduledTaskCreatePage';
import { ScheduledTaskDetailPage } from '@/features/scheduledTask/ScheduledTaskDetailPage';
import { ScheduledTaskEditPage } from '@/features/scheduledTask/ScheduledTaskEditPage';
import { ScheduledTaskRunDetailPage } from '@/features/scheduledTask/ScheduledTaskRunDetailPage';
import { ScheduledTaskCapabilityGate } from '@/features/scheduledTask/ScheduledTaskCapabilityGate';
import { EnvironmentVariablesPage } from '@/features/environmentVariables/EnvironmentVariablesPage';
import { HelpCenterPage } from '@/features/help/HelpCenterPage';
import { SharedArtifactPage } from '@/features/workitem/SharedArtifactPage';

export function createAppRoutes(): RouteObject[] {
  return [
    { path: '/api/share/workitems/:token/:kind/:id', element: <SharedArtifactPage /> },
    {
      element: <AppLayout helpCenter />,
      children: [{ path: '/help', element: <HelpCenterPage /> }],
    },
    {
      element: <AuthLayout />,
      children: [
        { path: '/login', element: <LoginPage /> },
        { path: '/register', element: <RegisterPage /> },
      ],
    },
    {
      path: '/workspaces',
      element: <RouteGuard requireWorkspace={false}><WorkspaceSelectPage /></RouteGuard>,
    },
    {
      path: '/workspaces/branding',
      element: <RouteGuard requireWorkspace={false}><BrandingConfigPage /></RouteGuard>,
    },
    {
      // requireWorkspace is false on purpose: the user may have just deleted the workspace their
      // token was bound to, and F5 says restore must not depend on that workspace at all.
      path: '/workspaces/recycle-bin',
      element: <RouteGuard requireWorkspace={false}><WorkspaceRecycleBinPage /></RouteGuard>,
    },
    {
      element: <RouteGuard requireWorkspace={false}><AppLayout /></RouteGuard>,
      children: [
        { path: '/profile/settings', element: <ProfileSettingsPage /> },
      ],
    },
    {
      path: '/platform/branding',
      element: <Navigate to="/workspaces" replace />,
    },
    {
      path: '/open-platform',
      element: <Navigate to="/profile/settings?tab=mcp" replace />,
    },
    {
      element: <RouteGuard><AppLayout /></RouteGuard>,
      children: [
        { index: true, element: <Navigate to="/workitems" replace /> },
        { path: '/workitems', element: <WorkitemListPage /> },
        { path: '/workitems/new', element: <WorkitemCreatePage /> },
        { path: '/workitems/:id', element: <WorkitemDetailPage /> },
        { path: '/agents', element: <AgentsHubPage /> },
        { path: '/agents/reviews', element: <RedirectWithQuery to="/agents" tab="reviews" /> },
        { path: '/agents/new', element: <AgentCreatePage /> },
        { path: '/agents/:id', element: <AgentDetailPage /> },
        { path: '/agents/:id/edit', element: <AgentEditPage /> },
        { path: '/squads', element: <RedirectWithQuery to="/agents" tab="squads" /> },
        { path: '/sdlcs', element: <SdlcListPage /> },
        { path: '/sdlcs/generate', element: <SdlcGeneratePage /> },
        { path: '/sdlcs/:id', element: <SdlcDetailPage /> },
        { path: '/repos', element: <ReposHubPage /> },
        { path: '/repos/map', element: <RedirectWithQuery to="/repos" tab="map" /> },
        { path: '/repos/:id', element: <RepoDetailPage /> },
        { path: '/memories', element: <MemoryListPage /> },
        { path: '/skills', element: <SkillListPage /> },
        { path: '/executors', element: <ExecutorListPage /> },
        { path: '/scheduled-tasks', element: <ScheduledTaskCapabilityGate><ScheduledTaskListPage /></ScheduledTaskCapabilityGate> },
        { path: '/scheduled-tasks/new', element: <ScheduledTaskCapabilityGate><ScheduledTaskCreatePage /></ScheduledTaskCapabilityGate> },
        { path: '/scheduled-tasks/:id/edit', element: <ScheduledTaskCapabilityGate><ScheduledTaskEditPage /></ScheduledTaskCapabilityGate> },
        { path: '/scheduled-tasks/:id', element: <ScheduledTaskCapabilityGate><ScheduledTaskDetailPage /></ScheduledTaskCapabilityGate> },
        { path: '/scheduled-task-runs/:runId', element: <ScheduledTaskCapabilityGate><ScheduledTaskRunDetailPage /></ScheduledTaskCapabilityGate> },
        { path: '/executions', element: <ExecutionListPage /> },
        { path: '/status-templates', element: <StatusTemplatePage /> },
        { path: '/integrations', element: <IntegrationsHubPage /> },
        { path: '/integrations/aone', element: <Navigate to="/integrations" replace /> },
        { path: '/integrations/channels', element: <RedirectWithQuery to="/integrations" tab="channels" /> },
        { path: '/evolution', element: <EvolutionPage /> },
        { path: '/audit-logs', element: <AuditLogPage /> },
        { path: '/notifications', element: <NotificationCenterPage /> },
        { path: '/settings/members', element: <MemberRoleSettingsPage /> },
        { path: '/settings/members-roles', element: <Navigate to="/settings/members" replace /> },
        { path: '/settings/members-roles/:tab', element: <Navigate to="/settings/members" replace /> },
        { path: '/settings/roles', element: <Navigate to="/settings/members" replace /> },
        { path: '/settings', element: <SettingsPage /> },
        { path: '/settings/backups', element: <ProjectBackupsPage /> },
        { path: '/settings/environment-variables', element: <EnvironmentVariablesPage /> },
        { path: '/insights', element: <InsightsPage /> },
        { path: '/about', element: <AboutAutoWonderPage /> },
      ],
    },
    { path: '*', element: <Navigate to="/login" replace /> },
  ];
}

export const router = createBrowserRouter(createAppRoutes());
