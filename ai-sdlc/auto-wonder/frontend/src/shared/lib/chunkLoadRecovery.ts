import { useTranslation } from 'react-i18next';
import { createElement, Suspense, lazy, useMemo, useState, type ComponentType, type ComponentProps } from 'react';
import { ContentErrorBoundary } from '../components/ContentErrorBoundary';

export const MODULE_LOAD_TIMEOUT_MS = 15_000;

// Bound the loading state; the browser import cannot be cancelled, but late results are ignored.
function loadWithTimeout<T>(loader: () => Promise<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Module loading timed out')), MODULE_LOAD_TIMEOUT_MS);
    Promise.resolve().then(loader).then(resolve, reject).finally(() => clearTimeout(timer));
  });
}

// Reuse React.lazy's component constraint, including class components and inferred props.
type LoadableComponent = Awaited<ReturnType<Parameters<typeof lazy>[0]>>['default'];

// Keep the existing call sites; failures are now isolated locally, never by reloading the document.
export function lazyWithChunkReload<T extends LoadableComponent>(
  loader: () => Promise<{ default: T }>,
): ComponentType<ComponentProps<T>> {
  return function IsolatedLazyContent(props: ComponentProps<T>) {
    const { t } = useTranslation('common');
    const [attempt, setAttempt] = useState(0);
    // React.lazy caches rejections. An explicit retry must create a fresh lazy instance.
    const View = useMemo(() => lazy(() => loadWithTimeout(loader)), [attempt]);
    return createElement(ContentErrorBoundary, {
      onRetry: () => setAttempt(value => value + 1),
      children: createElement(Suspense, { fallback: createElement('div', { role: 'status', style: { padding: 24 } }, t('failure.loading')), children: createElement(View, props) }),
    });
  };
}
