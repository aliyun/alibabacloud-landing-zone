import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { EllipsisText } from './EllipsisText';

function mockMeasurements(overflowing: boolean) {
  Object.defineProperty(HTMLElement.prototype, 'scrollWidth', { configurable: true, get: () => (overflowing ? 200 : 100) });
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 100 });
}

afterEach(() => {
  delete (HTMLElement.prototype as unknown as Record<string, unknown>).scrollWidth;
  delete (HTMLElement.prototype as unknown as Record<string, unknown>).clientWidth;
  cleanup();
});

describe('EllipsisText', () => {
  it('shows the tooltip on hover only when content overflows', async () => {
    mockMeasurements(true);
    render(<EllipsisText tooltip="完整标题">被截断的标题</EllipsisText>);
    fireEvent.mouseEnter(screen.getByText('被截断的标题'));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('完整标题');
  });

  it('shows no tooltip when content fits', () => {
    mockMeasurements(false);
    render(<EllipsisText tooltip="完整标题">短标题</EllipsisText>);
    fireEvent.mouseEnter(screen.getByText('短标题'));
    expect(screen.queryByRole('tooltip')).toBeNull();
  });
});
