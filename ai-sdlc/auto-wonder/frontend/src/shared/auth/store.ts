import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { allows, isWorkspaceAccessLevel, isWorkspaceDomain } from './access';
import type { UserInfo, WorkspaceAccessLevel, WorkspaceDomain, WorkspaceInfo } from '@/shared/types/common';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserInfo | null;
  currentWorkspace: WorkspaceInfo | null;
  accessLevel: WorkspaceAccessLevel | null;
  readOnlyDomains: WorkspaceDomain[];

  setTokens: (access: string, refresh: string) => void;
  setAccessToken: (token: string) => void;
  setUser: (user: UserInfo) => void;
  setCurrentWorkspace: (workspace: WorkspaceInfo, accessLevel: WorkspaceAccessLevel) => void;
  clearCurrentWorkspace: () => void;
  setAccessLevel: (accessLevel: WorkspaceAccessLevel) => void;
  setReadOnlyDomains: (domains: WorkspaceDomain[]) => void;
  hasAccess: (required: WorkspaceAccessLevel) => boolean;
  isAuthenticated: () => boolean;
  clear: () => void;
}

interface PersistedAuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserInfo | null;
  currentWorkspace: WorkspaceInfo | null;
  accessLevel: WorkspaceAccessLevel | null;
  readOnlyDomains: WorkspaceDomain[];
}

export function migrateAuthState(persistedState: unknown): PersistedAuthState {
  const state = persistedState && typeof persistedState === 'object'
    ? persistedState as Record<string, unknown>
    : {};
  const currentWorkspace =
    (state.currentWorkspace ?? state.currentOrg ?? null) as WorkspaceInfo | null;

  const readOnlyDomains = state.readOnlyDomains ?? state.forbiddenDomains;
  return {
    accessToken: typeof state.accessToken === 'string' ? state.accessToken : null,
    refreshToken: typeof state.refreshToken === 'string' ? state.refreshToken : null,
    user: (state.user ?? null) as UserInfo | null,
    currentWorkspace,
    accessLevel: isWorkspaceAccessLevel(state.accessLevel)
      ? state.accessLevel
      : currentWorkspace ? 'READ_ONLY' : null,
    readOnlyDomains: Array.isArray(readOnlyDomains)
      ? readOnlyDomains.filter(isWorkspaceDomain)
      : [],
  };
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      currentWorkspace: null,
      accessLevel: null,
      readOnlyDomains: [],

      setTokens: (access, refresh) => set({ accessToken: access, refreshToken: refresh }),
      setAccessToken: (token) => set({ accessToken: token }),
      setUser: (user) => set({ user }),
      setCurrentWorkspace: (workspace, accessLevel) => set({
        // 进入新空间时角色只读功能快照一律作废，由 membership 同步重新填充，避免跨空间残留。
        currentWorkspace: workspace, accessLevel, readOnlyDomains: [],
      }),
      clearCurrentWorkspace: () => set({ currentWorkspace: null, accessLevel: null, readOnlyDomains: [] }),
      setAccessLevel: (accessLevel) => set({ accessLevel }),
      setReadOnlyDomains: (domains) => set({ readOnlyDomains: domains }),
      hasAccess: (required) => allows(get().accessLevel, required),
      isAuthenticated: () => get().accessToken !== null,
      clear: () => set({
        accessToken: null,
        refreshToken: null,
        user: null,
        currentWorkspace: null,
        accessLevel: null,
        readOnlyDomains: [],
      }),
    }),
    {
      name: 'aw-auth',
      version: 3,
      migrate: (persistedState) => migrateAuthState(persistedState),
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        user: state.user,
        currentWorkspace: state.currentWorkspace,
        accessLevel: state.accessLevel,
        readOnlyDomains: state.readOnlyDomains,
      }),
    }
  )
);
