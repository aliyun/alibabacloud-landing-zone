import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { AuthLayout } from './AuthLayout';
import { AppLayout } from './AppLayout';
import { RouteGuard } from './RouteGuard';
import { LoginPage } from '@/features/auth/LoginPage';
import { RegisterPage } from '@/features/auth/RegisterPage';
import { OrgSelectPage } from '@/features/auth/OrgSelectPage';
import { WorkitemListPage } from '@/features/workitem/WorkitemListPage';
import { WorkitemCreatePage } from '@/features/workitem/WorkitemCreatePage';
import { WorkitemDetailPage } from '@/features/workitem/WorkitemDetailPage';
import { AgentListPage } from '@/features/agent/AgentListPage';
import { AgentCreatePage } from '@/features/agent/AgentCreatePage';
import { AgentDetailPage } from '@/features/agent/AgentDetailPage';
import { AgentEditPage } from '@/features/agent/AgentEditPage';
import { AgentReviewPage } from '@/features/agent/AgentReviewPage';
import { SquadListPage } from '@/features/squad/SquadListPage';
import { ExecutorListPage } from '@/features/executor/ExecutorListPage';
import { ExecutionListPage } from '@/features/execution/ExecutionListPage';
import { SdlcListPage } from '@/features/sdlc/SdlcListPage';
import { SdlcGeneratePage } from '@/features/sdlc/SdlcGeneratePage';
import { SdlcDetailPage } from '@/features/sdlc/SdlcDetailPage';
import { RepoListPage } from '@/features/repo/RepoListPage';
import { RepoDetailPage } from '@/features/repo/RepoDetailPage';
import { RepoMapPage } from '@/features/repo/RepoMapPage';
import { MemoryListPage } from '@/features/memory/MemoryListPage';
import { MemoryImportPage } from '@/features/memory/MemoryImportPage';
import { MemoryReviewPage } from '@/features/memory/MemoryReviewPage';
import { SkillListPage } from '@/features/skill/SkillListPage';
import { AuditLogPage } from '@/features/audit/AuditLogPage';
import { SettingsPage } from '@/features/settings/SettingsPage';
import { MemberRoleSettingsPage } from '@/features/settings/MemberRoleSettingsPage';
import { StatusTemplatePage } from '@/features/statemachine/StatusTemplatePage';
import { InsightsPage } from '@/features/insights/InsightsPage';
import { WorkitemIntegrationPage } from '@/features/integration/WorkitemIntegrationPage';
import { ChannelIntegrationPage } from '@/features/integration/channels/ChannelIntegrationPage';
import { AboutAutoWonderPage } from '@/features/about/AboutAutoWonderPage';
import { EvolutionPage } from '@/features/evolution/EvolutionPage';
import { BrandingConfigPage } from '@/features/platform/BrandingConfigPage';
import { ProfileSettingsPage } from '@/features/profile/ProfileSettingsPage';

export function createAppRoutes(): RouteObject[] {
  return [
    {
      element: <AuthLayout />,
      children: [
        { path: '/login', element: <LoginPage /> },
        { path: '/register', element: <RegisterPage /> },
      ],
    },
    {
      path: '/orgs',
      element: <RouteGuard requireOrg={false}><OrgSelectPage /></RouteGuard>,
    },
    {
      path: '/orgs/branding',
      element: <RouteGuard requireOrg={false}><BrandingConfigPage /></RouteGuard>,
    },
    {
      element: <RouteGuard requireOrg={false}><AppLayout /></RouteGuard>,
      children: [
        { path: '/profile/settings', element: <ProfileSettingsPage /> },
      ],
    },
    {
      path: '/platform/branding',
      element: <Navigate to="/orgs" replace />,
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
        { path: '/agents', element: <AgentListPage /> },
        { path: '/agents/reviews', element: <AgentReviewPage /> },
        { path: '/agents/new', element: <AgentCreatePage /> },
        { path: '/agents/:id', element: <AgentDetailPage /> },
        { path: '/agents/:id/edit', element: <AgentEditPage /> },
        { path: '/squads', element: <SquadListPage /> },
        { path: '/sdlcs', element: <SdlcListPage /> },
        { path: '/sdlcs/generate', element: <SdlcGeneratePage /> },
        { path: '/sdlcs/:id', element: <SdlcDetailPage /> },
        { path: '/repos', element: <RepoListPage /> },
        { path: '/repos/map', element: <RepoMapPage /> },
        { path: '/repos/:id', element: <RepoDetailPage /> },
        { path: '/memories', element: <MemoryListPage /> },
        { path: '/memories/import', element: <MemoryImportPage /> },
        { path: '/memories/reviews', element: <MemoryReviewPage /> },
        { path: '/skills', element: <SkillListPage /> },
        { path: '/executors', element: <ExecutorListPage /> },
        { path: '/executions', element: <ExecutionListPage /> },
        { path: '/status-templates', element: <StatusTemplatePage /> },
        { path: '/integrations', element: <WorkitemIntegrationPage /> },
        { path: '/integrations/aone', element: <Navigate to="/integrations" replace /> },
        { path: '/integrations/channels', element: <ChannelIntegrationPage /> },
        { path: '/evolution', element: <EvolutionPage /> },
        { path: '/audit-logs', element: <AuditLogPage /> },
        { path: '/settings/members', element: <MemberRoleSettingsPage /> },
        { path: '/settings/members-roles', element: <Navigate to="/settings/members" replace /> },
        { path: '/settings/members-roles/:tab', element: <Navigate to="/settings/members" replace /> },
        { path: '/settings/roles', element: <Navigate to="/settings/members" replace /> },
        { path: '/settings', element: <SettingsPage /> },
        { path: '/insights', element: <InsightsPage /> },
        { path: '/about', element: <AboutAutoWonderPage /> },
      ],
    },
    { path: '*', element: <Navigate to="/login" replace /> },
  ];
}

export const router = createBrowserRouter(createAppRoutes());
