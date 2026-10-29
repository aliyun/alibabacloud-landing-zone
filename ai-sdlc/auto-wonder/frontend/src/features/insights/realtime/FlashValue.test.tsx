import { act, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import FlashValue from './FlashValue';

afterEach(() => {
  vi.useRealTimers();
});

it('does not flash on the first render', () => {
  render(<FlashValue value={7} />);

  expect(screen.getByText('7')).not.toHaveClass('is-flash');
});

it('flashes a changed value for 600ms and then settles', () => {
  vi.useFakeTimers();
  const { rerender } = render(<FlashValue value={7} />);

  rerender(<FlashValue value={9} />);
  expect(screen.getByText('9')).toHaveClass('is-flash');

  act(() => { vi.advanceTimersByTime(599); });
  expect(screen.getByText('9')).toHaveClass('is-flash');

  act(() => { vi.advanceTimersByTime(1); });
  expect(screen.getByText('9')).not.toHaveClass('is-flash');
});

it('does not restart the flash when rerendered with the value already shown', () => {
  vi.useFakeTimers();
  const { rerender } = render(<FlashValue value={7} />);

  rerender(<FlashValue value={9} />);
  rerender(<FlashValue value={9} />);
  act(() => { vi.advanceTimersByTime(600); });

  expect(screen.getByText('9')).not.toHaveClass('is-flash');
});

it('flashes again when the value changes back after settling', () => {
  vi.useFakeTimers();
  const { rerender } = render(<FlashValue value={7} />);

  rerender(<FlashValue value={9} />);
  act(() => { vi.advanceTimersByTime(600); });
  rerender(<FlashValue value={7} />);

  expect(screen.getByText('7')).toHaveClass('is-flash');
});
