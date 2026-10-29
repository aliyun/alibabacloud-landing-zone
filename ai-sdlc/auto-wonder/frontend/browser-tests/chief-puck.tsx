import React, { useState } from 'react';
import { Button, Modal } from 'antd';
import { ElicitationWizard } from '../src/features/workitem/clarification/cards/ElicitationWizard';
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
// Visual fixture: product Chief and Wizard with the existing fullscreen layer contract.
function LayeringFixture() {
  const [fullscreen, setFullscreen] = useState(false);
  const [modal, setModal] = useState(false);
  const [answered, setAnswered] = useState(false);
  return <>
    <Button onClick={() => setFullscreen(true)}>Open fullscreen fixture</Button>
    <Button onClick={() => setModal(true)}>Open modal fixture</Button>
    <output data-testid="layer-answer">{String(answered)}</output>
    {fullscreen && <div data-testid="layer-fullscreen" style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'white', display: 'flex', flexDirection: 'column' }}>
      <Button onClick={() => setFullscreen(false)}>Exit fullscreen fixture</Button>
      <div style={{ flex: 1 }} />
      <ElicitationWizard requestId="layer" fullscreen message="Layer question" schema={{ type: 'object', properties: { choice: { type: 'string', title: 'Layer choice' } }, required: ['choice'] }} onReply={() => setAnswered(true)} />
    </div>}
    <Modal title="Layer modal" open={modal} onCancel={() => setModal(false)} onOk={() => setModal(false)}>Modal interaction</Modal>
  </>;
}
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/workitems/128']}>
          <ChiefOfStaff />
          {new URLSearchParams(location.search).has('layering') && <LayeringFixture />}
        </MemoryRouter>
      </QueryClientProvider>
    </AppearanceProvider>
  </LocaleProvider>,
);
