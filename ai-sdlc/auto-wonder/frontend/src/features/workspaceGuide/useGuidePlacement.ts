import { useLayoutEffect, useState, type CSSProperties, type RefObject } from 'react';

type Side = 'top' | 'bottom' | 'left' | 'right';
type Target = 'setup' | 'copy' | 'wait' | 'entry' | 'send';
interface Placement {
  side: Side;
  position: CSSProperties;
  connector: { path: string; arrow: string };
}

const inset = 16;
const gap = 48;
const maxGap = 180;
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(value, max));
type Rect = Pick<DOMRect, 'left' | 'top' | 'right' | 'bottom' | 'width' | 'height'>;
type Point = { x: number; y: number };

function connector(side: Side, card: Rect, anchor: Rect): Placement['connector'] {
  const vertical = side === 'top' || side === 'bottom';
  const cardCenter = vertical ? (card.left + card.right) / 2 : (card.top + card.bottom) / 2;
  const anchorCenter = vertical ? (anchor.left + anchor.right) / 2 : (anchor.top + anchor.bottom) / 2;
  const startCross = clamp(anchorCenter, (vertical ? card.left : card.top) + 16,
    (vertical ? card.right : card.bottom) - 16);
  const endCross = clamp(cardCenter, vertical ? anchor.left : anchor.top, vertical ? anchor.right : anchor.bottom);
  const start: Point = vertical
    ? { x: startCross, y: side === 'top' ? card.bottom : card.top }
    : { x: side === 'left' ? card.right : card.left, y: startCross };
  const end: Point = vertical
    ? { x: endCross, y: side === 'top' ? anchor.top - 10 : anchor.bottom + 10 }
    : { x: side === 'left' ? anchor.left - 10 : anchor.right + 10, y: endCross };
  // Both controls stay in the clear strip between the card and the target.
  const middle = vertical ? (start.y + end.y) / 2 : (start.x + end.x) / 2;
  const c1 = vertical ? { x: Math.min(start.x + 28, card.right - 8), y: middle }
    : { x: middle, y: Math.min(start.y + 28, card.bottom - 8) };
  const c2 = vertical ? { x: end.x, y: middle } : { x: middle, y: end.y };
  const direction = side === 'top' || side === 'left' ? 1 : -1;
  const wing1 = vertical ? { x: end.x - 4, y: end.y - direction * 7 }
    : { x: end.x - direction * 7, y: end.y - 4 };
  const wing2 = vertical ? { x: end.x + 4, y: end.y - direction * 7 }
    : { x: end.x - direction * 7, y: end.y + 4 };
  const point = ({ x, y }: Point) => `${x},${y}`;
  return {
    path: `M ${point(start)} C ${point(c1)} ${point(c2)} ${point(end)}`,
    arrow: `M ${point(wing1)} L ${point(end)} L ${point(wing2)}`,
  };
}

export function findPlacement(card: Pick<DOMRect, 'width' | 'height'>, target: Rect,
  viewport: { width: number; height: number }): Placement | null {
  const { width, height } = viewport;
  if (!target.width || !target.height || target.right <= 0 || target.bottom <= 0
    || target.left >= width || target.top >= height || card.width > width - inset * 2) return null;
  const anchor = {
    left: Math.max(0, target.left), top: Math.max(0, target.top),
    right: Math.min(width, target.right), bottom: Math.min(height, target.bottom),
    width: Math.min(width, target.right) - Math.max(0, target.left),
    height: Math.min(height, target.bottom) - Math.max(0, target.top),
  };
  const cardWidth = card.width;
  const naturalHeight = Math.min(card.height, height - inset * 2);
  const centerLeft = (width - cardWidth) / 2;
  const sides: Side[] = ['top', 'bottom', 'left', 'right'];
  const candidates: { side: Side; rect: Rect; distance: number }[] = [];
  for (const side of sides) {
    const vertical = side === 'top' || side === 'bottom';
    const space = side === 'top' ? anchor.top - gap - inset
      : side === 'bottom' ? height - inset - anchor.bottom - gap
        : side === 'left' ? anchor.left - gap - inset : width - inset - anchor.right - gap;
    const cardHeight = vertical ? Math.min(naturalHeight, space) : naturalHeight;
    if (vertical ? cardHeight < Math.min(140, naturalHeight) : space < cardWidth) continue;
    const desiredTop = (height - cardHeight) / 2;
    let left: number;
    let top: number;
    if (vertical) {
      left = clamp(centerLeft, inset, width - inset - cardWidth);
      // Keep cross-axis travel short even when the target is in a corner.
      left = clamp(left, Math.max(inset, anchor.left - cardWidth - 80),
        Math.min(width - inset - cardWidth, anchor.right + 80));
      top = side === 'top'
        ? clamp(desiredTop, Math.max(inset, anchor.top - maxGap - cardHeight), anchor.top - gap - cardHeight)
        : clamp(desiredTop, anchor.bottom + gap, Math.min(height - inset - cardHeight, anchor.bottom + maxGap));
    } else {
      left = side === 'left'
        ? clamp(centerLeft, Math.max(inset, anchor.left - maxGap - cardWidth), anchor.left - gap - cardWidth)
        : clamp(centerLeft, anchor.right + gap, Math.min(width - inset - cardWidth, anchor.right + maxGap));
      top = clamp(desiredTop, Math.max(inset, anchor.top - cardHeight - 80),
        Math.min(height - inset - cardHeight, anchor.bottom + 80));
    }
    const rect = { left, top, right: left + cardWidth, bottom: top + cardHeight, width: cardWidth, height: cardHeight };
    // Prefer a full-height card; among those, choose the closest to screen center.
    const distance = Math.hypot(left - centerLeft, top + cardHeight / 2 - height / 2)
      + (naturalHeight - cardHeight) * 4;
    candidates.push({ side, rect, distance });
  }
  const choice = candidates.sort((a, b) => a.distance - b.distance)[0];
  if (!choice) return null;
  return {
    side: choice.side,
    position: { top: choice.rect.top, left: choice.rect.left, transform: 'none', maxHeight: choice.rect.height },
    connector: connector(choice.side, choice.rect, anchor),
  };
}

export function findPanelPlacement(card: Pick<DOMRect, 'width' | 'height'>, panel: Rect,
  button: Rect, viewport: { width: number; height: number }, copyingCommand = false): Placement | null {
  const placement = findPlacement(card, panel, viewport);
  if (!placement) return null;
  if (panel.right + gap + card.width <= viewport.width - inset) {
    const left = panel.right + gap;
    const top = clamp((viewport.height - card.height) / 2, inset, viewport.height - inset - card.height);
    const row = button.bottom + 8;
    const startY = clamp(row, top + 16, top + card.height - 16);
    const endX = (button.left + button.right) / 2;
    return {
      side: 'right',
      position: { left, top, transform: 'none', maxHeight: card.height },
      connector: copyingCommand ? connector('right', { left, top, right: left + card.width,
        bottom: top + card.height, width: card.width, height: card.height },
        { left: button.left, right: button.right, width: button.width, height: 0,
          top: button.top + button.height / 2, bottom: button.top + button.height / 2 }) : {
        path: `M ${left},${startY} C ${left - (left - endX) * 0.35},${startY + 32} ${endX},${button.bottom + 72} ${endX},${button.bottom + 30} C ${endX},${button.bottom + 25} ${endX},${button.bottom + 21} ${endX},${button.bottom + 16}`,
        arrow: `M ${endX - 4},${button.bottom + 23} L ${endX},${button.bottom + 16} L ${endX + 4},${button.bottom + 23}`,
      },
    };
  }
  // Vertical layouts also avoid the entire panel, keeping the connector outside its content.
  const bounds = { left: Number(placement.position.left), top: Number(placement.position.top),
    width: card.width, height: Number(placement.position.maxHeight),
    right: Number(placement.position.left) + card.width,
    bottom: Number(placement.position.top) + Number(placement.position.maxHeight) };
  return { ...placement, connector: connector(placement.side, bounds, button) };
}

export function useGuidePlacement(cardRef: RefObject<HTMLElement>, target: Target | null): Placement | null {
  const [result, setResult] = useState<{ target: Target; placement: Placement } | null>(null);

  useLayoutEffect(() => {
    const card = cardRef.current;
    if (!card || !target) {
      setResult(null);
      return;
    }
    const content = card.querySelector<HTMLElement>('.aw-guide-card-content');
    let frame = 0;
    let observed: HTMLElement | null = null;
    const measure = () => {
      const isSetup = target === 'setup' || target === 'copy' || target === 'wait';
      const setup = isSetup ? document.querySelector('[data-guide-setup]') : null;
      const anchor = isSetup
        ? setup?.querySelector<HTMLElement>('.ant-btn-primary:not(:disabled)')
          ?? setup?.querySelector<HTMLElement>('.ant-btn:not(:disabled)') ?? null
        : document.querySelector<HTMLElement>(`[data-guide-target="${target}"]`);
      if (anchor !== observed) {
        if (observed) resize?.unobserve(observed);
        if (anchor) resize?.observe(anchor);
        observed = anchor;
      }
      const rect = card.getBoundingClientRect();
      const natural = new DOMRect(0, 0, rect.width, Math.min(content?.scrollHeight || rect.height, window.innerHeight - 32));
      const viewport = { width: window.innerWidth, height: window.innerHeight };
      const anchorRect = anchor?.getBoundingClientRect();
      let placement: Placement | null = null;
      if (anchorRect && (target === 'copy' || target === 'wait')) {
        const panel = setup?.closest('.aw-chief-guide-panel')?.getBoundingClientRect();
        if (panel) placement = findPanelPlacement(natural, panel, anchorRect, viewport, target === 'copy');
      } else if (anchorRect) {
        placement = findPlacement(natural, anchorRect, viewport);
      }
      setResult(current => {
        if (!placement) return null;
        if (current?.target === target && JSON.stringify(current.placement) === JSON.stringify(placement)) return current;
        return { target, placement };
      });
    };
    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(measure);
    };
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    resize?.observe(card);
    if (content) resize?.observe(content);
    const mutations = new MutationObserver(schedule);
    mutations.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'data-guide-target'] });
    window.addEventListener('resize', schedule);
    window.addEventListener('scroll', schedule, true);
    measure();
    return () => {
      cancelAnimationFrame(frame);
      resize?.disconnect();
      mutations.disconnect();
      window.removeEventListener('resize', schedule);
      window.removeEventListener('scroll', schedule, true);
    };
  }, [cardRef, target]);

  return result?.target === target ? result.placement : null;
}
