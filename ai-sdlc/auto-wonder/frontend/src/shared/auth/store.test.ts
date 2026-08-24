import { describe, it, expect, beforeEach } from 'vitest';
import { migrateAuthState, useAuthStore } from './store';

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('starts with no auth', () => {
    const state = useAuthStore.getState();
    expect(state.accessToken).toBeNull();
    expect(state.user).toBeNull();
    expect(state.currentWorkspace).toBeNull();
    expect(state.isAuthenticated()).toBe(false);
  });

  it('setTokens stores tokens', () => {
    useAuthStore.getState().setTokens('access-1', 'refresh-1');
    const state = useAuthStore.getState();
    expect(state.accessToken).toBe('access-1');
    expect(state.refreshToken).toBe('refresh-1');
  });

  it('setUser stores user info', () => {
    useAuthStore.getState().setUser({ id: 1, username: 'alice', nickname: 'Alice', email: 'a@b.com' });
    expect(useAuthStore.getState().user?.username).toBe('alice');
  });

  it('isAuthenticated is true when accessToken is set', () => {
    expect(useAuthStore.getState().isAuthenticated()).toBe(false);
    useAuthStore.getState().setTokens('token', 'refresh');
    expect(useAuthStore.getState().isAuthenticated()).toBe(true);
  });

  it('setCurrentWorkspace stores workspace and access level', () => {
    useAuthStore.getState().setCurrentWorkspace(
      { id: 10, name: 'Workspace1', description: '' },
      'READ_WRITE',
    );
    const state = useAuthStore.getState();
    expect(state.currentWorkspace?.id).toBe(10);
    expect(state.accessLevel).toBe('READ_WRITE');
  });

  it('clearCurrentWorkspace keeps the login session but clears workspace access', () => {
    useAuthStore.getState().setTokens('access', 'refresh');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 10, name: 'Workspace1', description: '' },
      'ADMIN',
    );

    useAuthStore.getState().clearCurrentWorkspace();

    expect(useAuthStore.getState().accessToken).toBe('access');
    expect(useAuthStore.getState().refreshToken).toBe('refresh');
    expect(useAuthStore.getState().currentWorkspace).toBeNull();
    expect(useAuthStore.getState().accessLevel).toBeNull();
  });

  it('hasAccess compares the current workspace access level', () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    expect(useAuthStore.getState().hasAccess('READ_ONLY')).toBe(true);
    expect(useAuthStore.getState().hasAccess('READ_WRITE')).toBe(true);
    expect(useAuthStore.getState().hasAccess('ADMIN')).toBe(false);
  });

  it('migrates an old persisted workspace to read only without retaining permissions', () => {
    const migrated = migrateAuthState({
      accessToken: 'access',
      refreshToken: 'refresh',
      user: null,
      currentWorkspace: { id: 1, name: 'Legacy', description: '' },
      permissions: ['workspace:manage'],
    });

    expect(migrated.accessLevel).toBe('READ_ONLY');
    expect(migrated).not.toHaveProperty('permissions');
  });

  it('keeps access level null when an old persisted store has no current workspace', () => {
    const migrated = migrateAuthState({
      accessToken: 'access',
      refreshToken: 'refresh',
      user: null,
      currentWorkspace: null,
      permissions: ['workspace:manage'],
    });

    expect(migrated.accessLevel).toBeNull();
  });

  it('migrates the legacy persisted currentOrg key to currentWorkspace', () => {
    const migrated = migrateAuthState({
      accessToken: 'access',
      refreshToken: 'refresh',
      user: null,
      currentOrg: { id: 9, name: 'LegacyWorkspace', description: '' },
    });

    expect(migrated.currentWorkspace?.id).toBe(9);
    expect(migrated.currentWorkspace?.name).toBe('LegacyWorkspace');
    expect(migrated.accessLevel).toBe('READ_ONLY');
  });

  it('clear resets all state', () => {
    useAuthStore.getState().setTokens('a', 'r');
    useAuthStore.getState().setUser({ id: 1, username: 'x', nickname: 'X', email: '' });
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessLevel).toBeNull();
  });
});
