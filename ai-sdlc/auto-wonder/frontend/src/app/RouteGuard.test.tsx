import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/shared/auth/store';
import { RouteGuard } from './RouteGuard';

describe('RouteGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('allows a read-only member to enter organization routes', () => {
    useAuthStore.getState().setTokens('access', 'refresh');
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');

    render(
      <MemoryRouter>
        <RouteGuard>
          <div>organization page</div>
        </RouteGuard>
      </MemoryRouter>,
    );

    expect(screen.getByText('organization page')).toBeInTheDocument();
    expect(screen.queryByText('无权限')).not.toBeInTheDocument();
  });
});
