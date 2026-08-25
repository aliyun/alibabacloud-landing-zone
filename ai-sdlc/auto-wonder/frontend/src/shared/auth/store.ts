import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { allows, isWorkspaceAccessLevel } from './access';
import type { UserInfo, WorkspaceAccessLevel, WorkspaceInfo } from '@/shared/types/common';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserInfo | null;
  currentWorkspace: WorkspaceInfo | null;
  accessLevel: WorkspaceAccessLevel | null;

  setTokens: (access: string, refresh: string) => void;
  setAccessToken: (token: string) => void;
  setUser: (user: UserInfo) => void;
  setCurrentWorkspace: (workspace: WorkspaceInfo, accessLevel: WorkspaceAccessLevel) => void;
  clearCurrentWorkspace: () => void;
  setAccessLevel: (accessLevel: WorkspaceAccessLevel) => void;
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
}

export function migrateAuthState(persistedState: unknown): PersistedAuthState {
  const state = persistedState && typeof persistedState === 'object'
    ? persistedState as Record<string, unknown>
    : {};
  const currentWorkspace =
    (state.currentWorkspace ?? state.currentOrg ?? null) as WorkspaceInfo | null;

  return {
    accessToken: typeof state.accessToken === 'string' ? state.accessToken : null,
    refreshToken: typeof state.refreshToken === 'string' ? state.refreshToken : null,
    user: (state.user ?? null) as UserInfo | null,
    currentWorkspace,
    accessLevel: isWorkspaceAccessLevel(state.accessLevel)
      ? state.accessLevel
      : currentWorkspace ? 'READ_ONLY' : null,
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

      setTokens: (access, refresh) => set({ accessToken: access, refreshToken: refresh }),
      setAccessToken: (token) => set({ accessToken: token }),
      setUser: (user) => set({ user }),
      setCurrentWorkspace: (workspace, accessLevel) => set({ currentWorkspace: workspace, accessLevel }),
      clearCurrentWorkspace: () => set({ currentWorkspace: null, accessLevel: null }),
      setAccessLevel: (accessLevel) => set({ accessLevel }),
      hasAccess: (required) => allows(get().accessLevel, required),
      isAuthenticated: () => get().accessToken !== null,
      clear: () => set({
        accessToken: null,
        refreshToken: null,
        user: null,
        currentWorkspace: null,
        accessLevel: null,
      }),
    }),
    {
      name: 'aw-auth',
      version: 2,
      migrate: (persistedState) => migrateAuthState(persistedState),
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        user: state.user,
        currentWorkspace: state.currentWorkspace,
        accessLevel: state.accessLevel,
      }),
    }
  )
);
