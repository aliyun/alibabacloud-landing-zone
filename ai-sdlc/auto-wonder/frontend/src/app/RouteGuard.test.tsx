import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/shared/auth/store';
import { RouteGuard } from './RouteGuard';

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{`${location.pathname}${location.search}`}</span>;
}

describe('RouteGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('allows a read-only member to enter workspace routes', () => {
    useAuthStore.getState().setTokens('access', 'refresh');
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');

    render(
      <MemoryRouter>
        <RouteGuard>
          <div>workspace page</div>
        </RouteGuard>
      </MemoryRouter>,
    );

    expect(screen.getByText('workspace page')).toBeInTheDocument();
    expect(screen.queryByText('无权限')).not.toBeInTheDocument();
  });

  it('carries the blocked in-site target to workspace selection', () => {
    useAuthStore.getState().setTokens('access', 'refresh');

    render(
      <MemoryRouter initialEntries={['/workitems/42?tab=discussion']}>
        <Routes>
          <Route path="/workitems/:id" element={<RouteGuard><div>detail</div></RouteGuard>} />
          <Route path="/workspaces" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByTestId('location')).toHaveTextContent(
      '/workspaces?returnTo=%2Fworkitems%2F42%3Ftab%3Ddiscussion',
    );
  });

  it('does not carry an auth entry path as the return target', () => {
    useAuthStore.getState().setTokens('access', 'refresh');

    render(
      <MemoryRouter initialEntries={['/sso/complete?ticket=t-1']}>
        <Routes>
          <Route path="/sso/complete" element={<RouteGuard><div>complete</div></RouteGuard>} />
          <Route path="/workspaces" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByTestId('location')).toHaveTextContent(/^\/workspaces$/);
  });
});
