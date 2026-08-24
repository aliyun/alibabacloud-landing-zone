import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SquadTemplateGallery } from './SquadTemplateGallery';

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/sdlcs']}>
        <SquadTemplateGallery />
        <Routes>
          <Route path="*" element={<LocationDisplay />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const mockTemplates = [
  {
    id: 1,
    name: 'Solo Dev',
    description: 'Single agent squad',
    squadSize: 1,
    icon: 'solo',
    tags: ['simple'],
    system: true,
  },
];

const mockDetail = {
  id: 1,
  name: 'Solo Dev',
  description: 'Single agent squad',
  squadSize: 1,
  icon: 'solo',
  tags: ['simple'],
  system: true,
  squad: { name: 'Solo Squad', description: 'Auto-created' },
  agents: [
    {
      name: 'Dev Agent',
      roleCode: 'AW_DEV',
      roleName: 'Developer',
      responsibilities: 'Write code',
      sdlc: {
        name: 'Standard',
        description: 'Standard flow',
        steps: [{ order: 1, name: 'Code', kind: 'WORK' }],
      },
    },
  ],
};

describe('SquadTemplateGallery', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders template cards', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
    );
    renderPage();
    expect(await screen.findByText('Solo Dev')).toBeInTheDocument();
    expect(screen.getByText('1 人小队')).toBeInTheDocument();
  });

  it('navigates to /squads after applying a template', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
      http.get('/api/squad-templates/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockDetail,
      })),
      http.post('/api/squad-templates/1/apply', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { squadId: 42, agents: [{ agentId: 1, roleName: 'Developer', roleCode: 'AW_DEV' }] },
      })),
    );
    renderPage();

    const applyButtons = await screen.findAllByText('基于此模版创建');
    await userEvent.click(applyButtons[0]);

    const confirmBtn = await screen.findByText('确定创建');
    await userEvent.click(confirmBtn);

    await waitFor(() => {
      const locationEl = screen.getByTestId('location');
      expect(locationEl.textContent).toBe('/squads');
    });
  });
});
