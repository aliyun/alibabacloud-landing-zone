import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { allows, isOrgAccessLevel } from './access';
import type { UserInfo, OrgAccessLevel, OrgInfo } from '@/shared/types/common';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserInfo | null;
  currentOrg: OrgInfo | null;
  accessLevel: OrgAccessLevel | null;

  setTokens: (access: string, refresh: string) => void;
  setAccessToken: (token: string) => void;
  setUser: (user: UserInfo) => void;
  setCurrentOrg: (org: OrgInfo, accessLevel: OrgAccessLevel) => void;
  clearCurrentOrg: () => void;
  setAccessLevel: (accessLevel: OrgAccessLevel) => void;
  hasAccess: (required: OrgAccessLevel) => boolean;
  isAuthenticated: () => boolean;
  clear: () => void;
}

interface PersistedAuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserInfo | null;
  currentOrg: OrgInfo | null;
  accessLevel: OrgAccessLevel | null;
}

export function migrateAuthState(persistedState: unknown): PersistedAuthState {
  const state = persistedState && typeof persistedState === 'object'
    ? persistedState as Record<string, unknown>
    : {};
  const currentOrg = (state.currentOrg ?? null) as OrgInfo | null;

  return {
    accessToken: typeof state.accessToken === 'string' ? state.accessToken : null,
    refreshToken: typeof state.refreshToken === 'string' ? state.refreshToken : null,
    user: (state.user ?? null) as UserInfo | null,
    currentOrg,
    accessLevel: isOrgAccessLevel(state.accessLevel)
      ? state.accessLevel
      : currentOrg ? 'READ_ONLY' : null,
  };
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      currentOrg: null,
      accessLevel: null,

      setTokens: (access, refresh) => set({ accessToken: access, refreshToken: refresh }),
      setAccessToken: (token) => set({ accessToken: token }),
      setUser: (user) => set({ user }),
      setCurrentOrg: (org, accessLevel) => set({ currentOrg: org, accessLevel }),
      clearCurrentOrg: () => set({ currentOrg: null, accessLevel: null }),
      setAccessLevel: (accessLevel) => set({ accessLevel }),
      hasAccess: (required) => allows(get().accessLevel, required),
      isAuthenticated: () => get().accessToken !== null,
      clear: () => set({
        accessToken: null,
        refreshToken: null,
        user: null,
        currentOrg: null,
        accessLevel: null,
      }),
    }),
    {
      name: 'aw-auth',
      version: 1,
      migrate: (persistedState) => migrateAuthState(persistedState),
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        user: state.user,
        currentOrg: state.currentOrg,
        accessLevel: state.accessLevel,
      }),
    }
  )
);
