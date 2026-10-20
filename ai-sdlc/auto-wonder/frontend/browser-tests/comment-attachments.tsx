import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { useAuthStore } from '../src/shared/auth/store';
import { useRequirementDocuments, useUnifiedTimeline } from '../src/features/workitem/hooks';
import { CommentInput } from '../src/features/workitem/components/CommentInput';
import { UnifiedTimeline } from '../src/features/workitem/components/UnifiedTimeline';

useAuthStore.setState({ accessLevel: 'READ_WRITE' });
const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
function Fixture() {
  const { data: documents = [] } = useRequirementDocuments('1');
  const { data: items = [] } = useUnifiedTimeline('1');
  return <main style={{ maxWidth: 900, margin: '40px auto', padding: 16 }}>
    <UnifiedTimeline items={items} artifacts={documents} />
    <CommentInput workitemId="1" documents={documents} />
  </main>;
}
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}><LocaleProvider><AppearanceProvider accent="#f97316"><Fixture /></AppearanceProvider></LocaleProvider></QueryClientProvider>,
);
