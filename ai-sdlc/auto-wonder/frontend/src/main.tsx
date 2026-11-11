import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './app/App';
import { ContentErrorBoundary } from './shared/components/ContentErrorBoundary';
import { LocaleProvider } from './shared/i18n/LocaleProvider';


ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <LocaleProvider><ContentErrorBoundary><App /></ContentErrorBoundary></LocaleProvider>
  </React.StrictMode>,
);
