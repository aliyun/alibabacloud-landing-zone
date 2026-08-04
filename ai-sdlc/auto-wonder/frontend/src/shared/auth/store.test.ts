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
    expect(state.currentOrg).toBeNull();
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

  it('setCurrentOrg stores org and access level', () => {
    useAuthStore.getState().setCurrentOrg(
      { id: 10, name: 'Org1', description: '' },
      'READ_WRITE',
    );
    const state = useAuthStore.getState();
    expect(state.currentOrg?.id).toBe(10);
    expect(state.accessLevel).toBe('READ_WRITE');
  });

  it('clearCurrentOrg keeps the login session but clears organization access', () => {
    useAuthStore.getState().setTokens('access', 'refresh');
    useAuthStore.getState().setCurrentOrg(
      { id: 10, name: 'Org1', description: '' },
      'ADMIN',
    );

    useAuthStore.getState().clearCurrentOrg();

    expect(useAuthStore.getState().accessToken).toBe('access');
    expect(useAuthStore.getState().refreshToken).toBe('refresh');
    expect(useAuthStore.getState().currentOrg).toBeNull();
    expect(useAuthStore.getState().accessLevel).toBeNull();
  });

  it('hasAccess compares the current organization access level', () => {
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    expect(useAuthStore.getState().hasAccess('READ_ONLY')).toBe(true);
    expect(useAuthStore.getState().hasAccess('READ_WRITE')).toBe(true);
    expect(useAuthStore.getState().hasAccess('ADMIN')).toBe(false);
  });

  it('migrates an old persisted organization to read only without retaining permissions', () => {
    const migrated = migrateAuthState({
      accessToken: 'access',
      refreshToken: 'refresh',
      user: null,
      currentOrg: { id: 1, name: 'Legacy', description: '' },
      permissions: ['org:manage'],
    });

    expect(migrated.accessLevel).toBe('READ_ONLY');
    expect(migrated).not.toHaveProperty('permissions');
  });

  it('keeps access level null when an old persisted store has no current organization', () => {
    const migrated = migrateAuthState({
      accessToken: 'access',
      refreshToken: 'refresh',
      user: null,
      currentOrg: null,
      permissions: ['org:manage'],
    });

    expect(migrated.accessLevel).toBeNull();
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
