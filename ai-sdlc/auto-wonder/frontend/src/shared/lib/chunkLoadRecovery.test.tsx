import { createMemoryRouter, RouterProvider, useParams } from 'react-router-dom';
import { Suspense } from 'react';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { lazyWithChunkReload, MODULE_LOAD_TIMEOUT_MS } from './chunkLoadRecovery';
import { i18n } from '@/shared/i18n';
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });
describe('isolated lazy content', () => {
  it('renders a successful module', async () => {
    const View = lazyWithChunkReload(async () => ({ default: () => <div>loaded</div> }));
    render(<Suspense fallback="loading"><View /></Suspense>);
    expect(await screen.findByText('loaded')).toBeVisible();
  });
  it('preserves required and optional component props', async () => {
    function Greeting({ name, suffix = '!' }: { name: string; suffix?: string }) {
      return <div>Hello {name}{suffix}</div>;
    }
    const View = lazyWithChunkReload(async () => ({ default: Greeting }));
    render(<View name="Community" suffix="?" />);
    expect(await screen.findByText('Hello Community?')).toBeVisible();
  });
  it.each(['Failed to fetch dynamically imported module: private-url', 'HTTP 503', 'module evaluation failed'])('isolates %s and permits an explicit retry', async (message) => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    let fail = true;
    const View = lazyWithChunkReload(async () => {
      if (fail) throw new Error(message);
      return { default: () => <div>recovered</div> };
    });
    render(<><input aria-label="draft" defaultValue="unsaved text" /><Suspense fallback="loading"><View /></Suspense></>);
    expect(await screen.findByRole('alert')).toBeVisible();
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    expect(screen.getByLabelText('draft')).toHaveValue('unsaved text');
    fail = false;
    fireEvent.click(screen.getByRole('button', { name: i18n.t('failure.retry') }));
    expect(await screen.findByText('recovered')).toBeVisible();
    expect(screen.getByLabelText('draft')).toHaveValue('unsaved text');
  });
  it('isolates component render exceptions too', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const View = lazyWithChunkReload(async () => ({ default: () => { throw new Error('private render stack'); } }));
    render(<Suspense fallback="loading"><View /></Suspense>);
    expect(await screen.findByRole('alert')).toBeVisible();
    expect(screen.queryByText('private render stack')).not.toBeInTheDocument();
  });
  it('ends loading when an import never settles', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.useFakeTimers();
    const View = lazyWithChunkReload(() => new Promise<never>(() => {}));
    render(<Suspense fallback="loading"><View /></Suspense>);
    expect(screen.getByRole('status')).toBeVisible();
    await act(async () => { await vi.advanceTimersByTimeAsync(MODULE_LOAD_TIMEOUT_MS); });
    expect(screen.getByRole('alert')).toBeVisible();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

it('clears a failed page boundary when navigating to another item on the same route', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  function ItemPage() {
    const { id } = useParams();
    if (id === '42') throw new Error('item 42 rendering failed');
    return <div>healthy item {id}<input aria-label="page draft" defaultValue="original" /></div>;
  }
  const View = lazyWithChunkReload(async () => ({ default: ItemPage }));
  const router = createMemoryRouter([{ path: '/workitems/:id', element: <View /> }], { initialEntries: ['/workitems/42'] });
  render(<RouterProvider router={router} />);
  expect(await screen.findByRole('alert')).toBeVisible();
  await act(async () => { await router.navigate('/workitems/43'); });
  expect(await screen.findByText('healthy item 43')).toBeVisible();
  fireEvent.change(screen.getByLabelText('page draft'), { target: { value: 'unsaved' } });
  await act(async () => { await router.navigate('/workitems/44'); });
  expect(await screen.findByText('healthy item 44')).toBeVisible();
  expect(screen.getByLabelText('page draft')).toHaveValue('unsaved');
  router.dispose();
});
