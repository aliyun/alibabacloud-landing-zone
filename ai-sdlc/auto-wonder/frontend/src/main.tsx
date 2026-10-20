import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './app/App';
import { installChunkLoadRecovery } from './shared/lib/chunkLoadRecovery';
import { LocaleProvider } from './shared/i18n/LocaleProvider';

installChunkLoadRecovery();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <LocaleProvider><App /></LocaleProvider>
  </React.StrictMode>,
);
