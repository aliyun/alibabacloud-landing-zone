import { fireEvent, render, screen } from '@testing-library/react';
import { it, expect } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
