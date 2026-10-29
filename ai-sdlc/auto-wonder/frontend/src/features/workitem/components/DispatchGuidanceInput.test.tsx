import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { DispatchGuidanceInput } from './DispatchGuidanceInput';
import { getDispatchGuidance, sendDispatchGuidance } from '../api';
vi.mock('../api', () => ({ getDispatchGuidance: vi.fn(), sendDispatchGuidance: vi.fn() }));
vi.mock('@/shared/auth/useAccessCommand', () => ({ useAccessCommand: () => (_: string, __: string, action: () => void) => action() }));
const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
beforeEach(() => { vi.clearAllMocks(); vi.mocked(getDispatchGuidance).mockResolvedValue([]); });
afterEach(() => {
  if (originalCrypto) {
    Object.defineProperty(globalThis, 'crypto', originalCrypto);
  } else {
    Reflect.deleteProperty(globalThis, 'crypto');
  }
});
it('preserves instructions and retries with the same request key after a lost response', async () => {
  vi.mocked(sendDispatchGuidance).mockRejectedValueOnce(new Error('lost response')).mockResolvedValueOnce([
    { id: 1, dispatchId: 42, workitemId: 7, actorId: 3, content: 'redirect <script>', status: 'QUEUED', createdAt: '' },
  ]);
  render(<DispatchGuidanceInput dispatchId={42} />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'redirect <script>' } });
  fireEvent.click(screen.getByRole('button'));
  await screen.findByText('lost response');
  fireEvent.click(screen.getByRole('button'));
  await waitFor(() => expect(sendDispatchGuidance).toHaveBeenCalledTimes(2));
  const calls = vi.mocked(sendDispatchGuidance).mock.calls;
  expect(calls[0]).toEqual([42, 'redirect <script>', expect.any(String)]);
  expect(calls[1]).toEqual(calls[0]);
  await screen.findByText('redirect <script>');
  expect(screen.getByRole('textbox')).toHaveValue('');
});
it('does not submit blank guidance', () => {
  render(<DispatchGuidanceInput dispatchId={42} />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '   ' } });
  expect(screen.getByRole('button')).toBeDisabled();
  expect(sendDispatchGuidance).not.toHaveBeenCalled();
});
it('sends guidance with a generated key when randomUUID is unavailable', async () => {
  const realCrypto = globalThis.crypto;
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: { getRandomValues: realCrypto.getRandomValues.bind(realCrypto) },
  });
  vi.mocked(sendDispatchGuidance).mockResolvedValueOnce([]);
  render(<DispatchGuidanceInput dispatchId={42} />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'HTTP context guidance' } });
  fireEvent.click(screen.getByRole('button'));
  await waitFor(() => expect(sendDispatchGuidance).toHaveBeenCalledWith(
    42,
    'HTTP context guidance',
    expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/),
  ));
});
