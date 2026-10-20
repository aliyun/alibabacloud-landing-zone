import { fireEvent, render, screen } from '@testing-library/react';
import { it, expect } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http } from 'msw';
import { server } from '@/test/mocks/server';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING } from '@/features/platform/brandingApi';
import { PlatformBrand } from './PlatformBrand';

it.each(['/workspaces', '/workitems'])('shows the configured brand and navigates to %s', (to) => {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
  client.setQueryData(BRANDING_QUERY_KEY, { ...DEFAULT_BRANDING, platformName: '测试平台', logoUrl: '/custom-logo.svg' });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/detail']}><Routes>
    <Route path="/detail" element={<PlatformBrand to={to} />} />
    <Route path={to} element={<h1>目标列表</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  const link = screen.getByRole('link', { name: /测试平台，返回/ });
  expect(link).toHaveAttribute('href', to);
  expect(link.querySelector('img')).toHaveAttribute('src', '/custom-logo.svg');
  fireEvent.click(link);
  expect(screen.getByRole('heading', { name: '目标列表' })).toBeInTheDocument();
});

it('renders neutral placeholders instead of the default brand until branding data arrives', () => {
  server.use(http.get('/api/platform/branding/public', () => new Promise<Response>(() => {})));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { container } = render(<QueryClientProvider client={client}><MemoryRouter>
    <PlatformBrand to="/workspaces" />
    <PlatformBrand to="/workitems" collapsed />
  </MemoryRouter></QueryClientProvider>);
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(screen.queryByText('AutoWonder')).not.toBeInTheDocument();
  expect(container.querySelector('img')).toBeNull();
  const placeholders = container.querySelectorAll('span[aria-hidden="true"]');
  expect(placeholders).toHaveLength(2);
  expect(placeholders[0].children).toHaveLength(2);
  expect(placeholders[1].children).toHaveLength(1);
});

it('falls back to the bundled logo when the configured logo fails to load', () => {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
  client.setQueryData(BRANDING_QUERY_KEY, { ...DEFAULT_BRANDING, platformName: 'The Legion', logoUrl: '/legion-logo.svg' });
  render(<QueryClientProvider client={client}><MemoryRouter><PlatformBrand to="/workspaces" /></MemoryRouter></QueryClientProvider>);
  const img = screen.getByRole('link', { name: /The Legion，返回/ }).querySelector('img')!;
  expect(img).toHaveAttribute('src', '/legion-logo.svg');
  fireEvent.error(img);
  expect(img.getAttribute('src')).toMatch(/\/logo\.svg$/);
  expect(img.dataset.fallback).toBe('1');
  fireEvent.error(img);
  expect(img.getAttribute('src')).toMatch(/\/logo\.svg$/);
});

it('keeps the bundled logo when the platform has none configured', () => {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
  client.setQueryData(BRANDING_QUERY_KEY, { ...DEFAULT_BRANDING, platformName: 'The Legion', logoUrl: '' });
  render(<QueryClientProvider client={client}><MemoryRouter><PlatformBrand to="/workitems" /></MemoryRouter></QueryClientProvider>);
  const img = screen.getByRole('link', { name: /The Legion，返回/ }).querySelector('img')!;
  expect(img).toHaveAttribute('src', '/logo.svg');
});
