import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '../src/shared/auth/store';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { ChiefOfStaff } from '../src/features/chiefOfStaff/ChiefOfStaff';

useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/workitems/128']}>
          <ChiefOfStaff />
        </MemoryRouter>
      </QueryClientProvider>
    </AppearanceProvider>
  </LocaleProvider>,
);
