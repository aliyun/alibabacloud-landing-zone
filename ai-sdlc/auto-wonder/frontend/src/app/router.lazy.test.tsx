import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, Link, Outlet, RouterProvider, useLocation, useParams } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { createAppRoutes } from './router';

const loaded = vi.hoisted(() => ({ detail: vi.fn(), repos: vi.fn() }));

vi.mock('@/features/workitem/WorkitemListPage', () => ({
  WorkitemListPage: () => <Link to="/workitems/42">open workitem</Link>,
}));
vi.mock('@/features/workitem/WorkitemDetailPage', () => {
  loaded.detail();
  return {
    WorkitemDetailPage: () => <div>workitem {useParams().id}<Link to="/repos?tab=map">open repo map</Link></div>,
  };
});
vi.mock('@/features/repo/ReposHubPage', () => {
  loaded.repos();
  return { ReposHubPage: () => <div>repositories {useLocation().search}</div> };
});

describe('workitem entry route loading', () => {
  it('loads the editor and repository map only when navigating to their pages', async () => {
    const routes = createAppRoutes().flatMap(route => route.children ?? [])
      .filter(route => ['/workitems', '/workitems/:id', '/repos'].includes(route.path ?? ''));
    const router = createMemoryRouter(routes, { initialEntries: ['/workitems'] });
    render(<RouterProvider router={router} />);

    expect(screen.getByRole('link', { name: 'open workitem' })).toBeInTheDocument();
    expect(loaded.detail).not.toHaveBeenCalled();
    expect(loaded.repos).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('link', { name: 'open workitem' }));
    expect(await screen.findByText('workitem 42')).toBeInTheDocument();
    expect(loaded.detail).toHaveBeenCalledOnce();
    expect(loaded.repos).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('link', { name: 'open repo map' }));
    expect(await screen.findByText('repositories ?tab=map')).toBeInTheDocument();
    expect(loaded.repos).toHaveBeenCalledOnce();
    router.dispose();
  });
});

 it('isolates route render failures while preserving the parent shell and navigation', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  function Broken() { throw new Error('private technical stack'); return null; }
  const route = createAppRoutes().flatMap(r => r.children ?? []).find(r => r.path === '/workitems/:id')!;
  const router = createMemoryRouter([{ element: <><Link to="/healthy">healthy navigation</Link><Outlet /></>, children: [
    { ...route, element: <Broken /> }, { path: '/healthy', element: <div>healthy page</div> },
  ] }], { initialEntries: ['/workitems/42'] });
  render(<RouterProvider router={router} />);
  expect(await screen.findByRole('link', { name: 'healthy navigation' })).toBeVisible();
  expect(screen.queryByText('Unexpected Application Error!')).not.toBeInTheDocument();
  expect(screen.queryByText('private technical stack')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('link', { name: 'healthy navigation' }));
  expect(await screen.findByText('healthy page')).toBeVisible();
  router.dispose();
  vi.restoreAllMocks();
 });
