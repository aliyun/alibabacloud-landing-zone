import { describe, expect, it } from 'vitest';
import { findPlacement, findPanelPlacement } from './useGuidePlacement';

const rect = (left: number, top: number, width: number, height: number) => ({
  left, top, width, height, right: left + width, bottom: top + height,
});
const viewport = { width: 1200, height: 800 };
const card = { width: 360, height: 240 };

describe('findPlacement', () => {
  it('keeps the card centered when the target has suitable separation', () => {
    const placement = findPlacement(card, rect(560, 620, 80, 40), viewport)!;
    expect(placement.position.left).toBe(420);
    expect(placement.position.top).toBe(280);
    expect(placement.side).toBe('top');
    expect(placement.connector.path).toMatch(/^M .+ C .+/);
    expect(placement.connector.arrow).toMatch(/^M .+ L .+ L .+/);
  });

  it('moves away from a target occupying the center', () => {
    const placement = findPlacement(card, rect(550, 360, 100, 80), viewport)!;
    const { top, left, maxHeight } = placement.position;
    expect(Number(top) + Number(maxHeight) <= 312 || Number(top) >= 488
      || Number(left) + card.width <= 502 || Number(left) >= 698).toBe(true);
  });

  it.each([
    rect(8, 360, 60, 40), rect(1132, 360, 60, 40),
    rect(560, 8, 80, 40), rect(560, 752, 80, 40),
    rect(8, 8, 60, 40), rect(1132, 752, 60, 40),
  ])('keeps edge-target cards on screen and provides finite paths: %o', target => {
    const placement = findPlacement(card, target, viewport)!;
    expect(placement).not.toBeNull();
    expect(Number(placement.position.left)).toBeGreaterThanOrEqual(16);
    expect(Number(placement.position.top)).toBeGreaterThanOrEqual(16);
    expect(Number(placement.position.left) + card.width).toBeLessThanOrEqual(1184);
    expect(Number(placement.position.top) + Number(placement.position.maxHeight)).toBeLessThanOrEqual(784);
    expect(placement.connector.path).not.toMatch(/NaN|Infinity/);
    // The cubic ends 10 px outside the target, with an open two-segment arrow.
    const end = placement.connector.path.split(' ').at(-1)!.split(',').map(Number);
    expect(placement.side === 'top' ? target.top - end[1]
      : placement.side === 'bottom' ? end[1] - target.bottom
        : placement.side === 'left' ? target.left - end[0] : end[0] - target.right).toBe(10);
    expect(placement.connector.arrow).not.toContain('Z');
  });

  it('limits card height on narrow screens while leaving the target uncovered', () => {
    const target = rect(20, 320, 335, 48);
    const placement = findPlacement({ width: 343, height: 400 }, target, { width: 375, height: 667 })!;
    expect(placement.position.maxHeight).toBeLessThan(400);
    expect(placement.position.left).toBe(16);
    expect(Number(placement.position.top) + Number(placement.position.maxHeight)).toBeLessThanOrEqual(target.top - 48);
  });

  it.each([rect(0, 0, 0, 20), rect(1300, 20, 60, 40), rect(20, -80, 60, 40)])(
    'hides the connector for hidden targets: %o', target => {
      expect(findPlacement(card, target, viewport)).toBeNull();
    },
  );
});

it.each([1440, 2048, 3826])('keeps setup cards 48px away from the full panel at %s px', width => {
  const panel = rect(24, 24, 560, 640);
  const button = rect(48, 420, 120, 48);
  const placement = findPanelPlacement({ width: 560, height: 320 }, panel, button, { width, height: 1000 })!;
  expect(placement.position.left).toBe(panel.right + 48);
  expect(placement.connector.path).toMatch(/^M [\d,.]+ C [\d,.]+ [\d,.]+ [\d,.]+ C [\d,.]+ [\d,.]+ [\d,.]+$/);
  expect(placement.connector.path.endsWith(`108,${button.bottom + 16}`)).toBe(true);
  expect(placement.connector.arrow).toContain(`L 108,${button.bottom + 16}`);
});

it('points the copy arrow at the button right edge with one smooth cubic', () => {
  const button = rect(48, 420, 120, 48);
  const placement = findPanelPlacement({ width: 560, height: 320 }, rect(24, 24, 560, 640),
    button, { width: 1440, height: 1000 }, true)!;
  expect(placement.connector.path).toMatch(/^M [\d,.]+ C [\d,.]+ [\d,.]+ [\d,.]+$/);
  expect(placement.connector.path.endsWith(`${button.right + 10},444`)).toBe(true);
  expect(placement.connector.arrow).toContain(`L ${button.right + 10},444`);
});
