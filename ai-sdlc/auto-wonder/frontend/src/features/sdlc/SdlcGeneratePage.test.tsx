import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { SdlcGeneratePage } from './SdlcGeneratePage';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';

vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: () => {} }));

beforeEach(() => useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY'));

it('keeps the direct AI route reactive and refuses AI commands in the current locale', async () => {
  const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  const router = createMemoryRouter([{ path: '/sdlcs/generate', element: <SdlcGeneratePage /> }], { initialEntries: ['/sdlcs/generate'] });
  render(<QueryClientProvider client={new QueryClient()}><RouterProvider router={router} /></QueryClientProvider>);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Generate SDLC with AI')).toBeInTheDocument();
  expect(screen.getByText('AI-generated workflows are saved as drafts (DRAFT). Enable them explicitly in the SDLC list before use.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(error).toHaveBeenCalledWith('Current access: Read-only access. Generate SDLC with AI requires Read and write access.');
  expect(router.state.location.pathname).toBe('/sdlcs/generate');
  error.mockRestore();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it('preserves the AI prompt, scene, raw result and confirmation navigation across a live switch', async () => {
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  const resultJson = '{"name":"<b>原工作流</b>","description":"原始 AI 内容","steps":[]}';
  let created: unknown;
  let confirmed: unknown;
  server.use(
    http.post('/api/ai/sessions', async ({ request }) => { created = await request.json(); return HttpResponse.json({ success: true, data: 100 }); }),
    http.get('/api/ai/sessions/100', () => HttpResponse.json({ success: true, data: { id: 100, scene: 'SDLC_GEN', bizRefType: 'ORG', bizRefId: 0, status: 'WAIT_USER', resultJson, error: null, gmtCreate: '2026-01-01', messages: [] } })),
    http.post('/api/ai/sessions/100/confirm', async ({ request }) => { confirmed = await request.json(); return HttpResponse.json({ success: true, data: null }); }),
  );
  const router = createMemoryRouter([{ path: '/sdlcs/generate', element: <SdlcGeneratePage /> }, { path: '/sdlcs', element: <div>Workflow destination</div> }], { initialEntries: ['/sdlcs/generate'] });
  render(<QueryClientProvider client={new QueryClient()}><RouterProvider router={router} /></QueryClientProvider>);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '原始 <i>prompt</i>' } });
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('textbox')).toHaveValue('原始 <i>prompt</i>');
  await userEvent.click(screen.getByRole('button', { name: /Send$/ }));
  expect(await screen.findByDisplayValue('<b>原工作流</b>')).toBeInTheDocument();
  expect(screen.getByDisplayValue('原始 AI 内容')).toBeInTheDocument();
  expect(created).toEqual({ scene: 'SDLC_GEN', bizRefType: 'ORG', bizRefId: 0, input: '原始 <i>prompt</i>' });
  await userEvent.click(screen.getByRole('button', { name: 'Save results' }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/sdlcs'));
  expect(confirmed).toEqual({ resultJson });
});
