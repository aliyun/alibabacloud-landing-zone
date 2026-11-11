import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { PrdPrototypePreview, prototypeDocument, prototypePages } from './PrdPrototypePreview';

function messageEvent(data: unknown, source?: unknown) {
  const event = new MessageEvent('message', { data });
  Object.defineProperty(event, 'source', { value: source });
  return event;
}
function frameToken(frame: HTMLIFrameElement): string {
  return /const token="([^"]+)"/.exec(frame.getAttribute('srcdoc') ?? '')![1];
}

describe('prototype preview', () => {
  it('blocks placeholder, external and form navigation while preserving valid in-page anchors', () => {
    const doc = document.implementation.createHTMLDocument();
    doc.body.innerHTML = '<a href="#">Members</a><a href="/members">Members route</a><a href="#section">Section</a><div id="section"></div><form></form>';
    const scroll = vi.fn();
    doc.getElementById('section')!.scrollIntoView = scroll;
    const parent = { postMessage: vi.fn() };
    const script = new DOMParser().parseFromString(prototypeDocument('', 'test-token'), 'text/html').querySelector('script')!.textContent!;
    new Function('document', 'parent', script)(doc, parent);
    for (const link of Array.from(doc.querySelectorAll('a'))) {
      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      link.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    }
    expect(scroll).toHaveBeenCalledOnce();
    expect(parent.postMessage).toHaveBeenCalledTimes(2);
    const submit = new Event('submit', { bubbles: true, cancelable: true });
    doc.querySelector('form')!.dispatchEvent(submit);
    expect(submit.defaultPrevented).toBe(true);
  });
  it('restores the initial document after script navigation and allows a manual reset', () => {
    render(<PrdPrototypePreview pages={[{ html: '<h1>Start</h1>' }]} />);
    const initial = screen.getByTitle('页面原型');
    fireEvent.load(initial);
    fireEvent.load(initial);
    expect(screen.getByTitle('页面原型')).not.toBe(initial);
    expect(screen.getByRole('alert')).toHaveTextContent('还没有对应的原型页面');
    const restored = screen.getByTitle('页面原型');
    fireEvent.click(screen.getByRole('button', { name: '重置预览' }));
    expect(screen.getByTitle('页面原型')).not.toBe(restored);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('renders a temporary prototype directly with an isolated document and mobile viewport', () => {
    const pages = prototypePages({ pages: [{ title: 'Resources', mockHtml: '<h1>Resources</h1><script>window.test=1</script>' }] });
    render(<PrdPrototypePreview pages={pages} viewport="mobile" />);
    const frame = screen.getByTitle('页面原型');
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts');
    expect(frame).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(frame.getAttribute('srcdoc')).toContain("default-src 'none'");
    expect(frame.getAttribute('srcdoc')).toContain('<h1>Resources</h1>');
    expect(frame).toHaveStyle({ width: '390px' });
  });
  it('picks the clicked element with stable selectors only while selection mode is on', () => {
    const parent = { postMessage: vi.fn() };
    const scripts = new DOMParser().parseFromString(prototypeDocument('', 'pick-token'), 'text/html').querySelectorAll('script');
    expect(scripts).toHaveLength(2);
    new Function('document', 'parent', 'window', scripts[1].textContent!)(document, parent, window);
    const host = document.createElement('div');
    host.innerHTML = '<section><button id="save">Save changes</button><button aria-label="Ghost">Ghost</button></section>';
    document.body.appendChild(host);
    const mode = (enabled: boolean) => window.dispatchEvent(messageEvent({ type: 'prd-selection-mode', token: 'pick-token', enabled }));
    const save = document.getElementById('save')!;
    save.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(parent.postMessage).not.toHaveBeenCalled();
    mode(true);
    expect(document.documentElement.style.cursor).toBe('crosshair');
    save.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    expect(save.style.outline).toBe('2px solid #1677ff');
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    save.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    expect(save.style.outline).toBe('');
    const picked = parent.postMessage.mock.calls[0][0];
    expect(picked).toMatchObject({ type: 'prd-element-selected', token: 'pick-token',
      element: { tag: 'button', selector: '#save', text: 'Save changes' } });
    expect(picked.element.cssPath).toContain('section > button:nth-of-type(1)');
    expect(picked.element.xpath).toContain('/section[1]/button[1]');
    const ghost = screen.queryByLabelText('Ghost') ?? document.querySelector('button[aria-label="Ghost"]')!;
    ghost.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(parent.postMessage.mock.calls[1][0].element).toMatchObject({ selector: 'button[aria-label="Ghost"]', tag: 'button' });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(parent.postMessage).toHaveBeenLastCalledWith({ type: 'prd-selection-cancel', token: 'pick-token' }, '*');
    mode(false);
    expect(document.documentElement.style.cursor).toBe('');
    host.remove();
  });
  it('toggles picking and reports the selected element with its page name', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    const onSelectingChange = vi.fn();
    const onSelectElement = vi.fn();
    const post = vi.fn();
    render(<PrdPrototypePreview pages={[{ name: 'Home', html: '<h1>Home</h1>' }]} onSelectingChange={onSelectingChange} onSelectElement={onSelectElement} />);
    const frame = screen.getByTitle('页面原型') as HTMLIFrameElement;
    Object.defineProperty(frame, 'contentWindow', { value: { postMessage: post }, configurable: true });
    fireEvent.load(frame);
    expect(post).toHaveBeenCalledWith({ type: 'prd-selection-mode', token: 'i', enabled: false }, '*');
    fireEvent.click(screen.getByRole('button', { name: '选取原型元素' }));
    expect(onSelectingChange).toHaveBeenCalledWith(true);
    window.dispatchEvent(messageEvent({ type: 'prd-element-selected', token: 'i', element: {
      tag: 'button', selector: '#save', cssPath: 'body > button', xpath: '/html[1]/body[1]/button[1]', text: 'Save',
    } }, frame.contentWindow));
    expect(onSelectElement).toHaveBeenCalledWith(expect.objectContaining({ selector: '#save', page: 'Home' }));
    expect(onSelectingChange).toHaveBeenLastCalledWith(false);
    window.dispatchEvent(messageEvent({ type: 'prd-selection-cancel', token: 'i' }, frame.contentWindow));
    expect(onSelectingChange).toHaveBeenCalledTimes(3);
    vi.restoreAllMocks();
  });
  it('shows the active picking state with its hint while selection mode is on', () => {
    const onSelectingChange = vi.fn();
    const hint = '在原型中点选要调整的按钮、文字或任意元素，选中后在左侧对话说明修改意见。';
    const { rerender } = render(<PrdPrototypePreview pages={[{ html: '<h1>Home</h1>' }]} onSelectingChange={onSelectingChange} />);
    expect(screen.getByRole('button', { name: '选取原型元素' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByText(hint)).not.toBeInTheDocument();
    rerender(<PrdPrototypePreview pages={[{ html: '<h1>Home</h1>' }]} selecting onSelectingChange={onSelectingChange} />);
    expect(screen.getByRole('button', { name: '退出选取' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(hint)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'close' }));
    expect(onSelectingChange).toHaveBeenCalledWith(false);
  });
  it('ignores element messages from another window or with a wrong token', () => {
    const onSelectElement = vi.fn();
    render(<PrdPrototypePreview pages={[{ html: '<h1>Home</h1>' }]} onSelectElement={onSelectElement} />);
    const frame = screen.getByTitle('页面原型') as HTMLIFrameElement;
    Object.defineProperty(frame, 'contentWindow', { value: { postMessage: vi.fn() }, configurable: true });
    const element = { tag: 'button', selector: '#save', cssPath: 'body > button', xpath: '/html[1]/body[1]/button[1]' };
    window.dispatchEvent(messageEvent({ type: 'prd-element-selected', token: frameToken(frame), element }, { postMessage: vi.fn() }));
    window.dispatchEvent(messageEvent({ type: 'prd-element-selected', token: `${frameToken(frame)}-other`, element }, frame.contentWindow));
    expect(onSelectElement).not.toHaveBeenCalled();
  });
  it('shows the navigation notice when the prototype reports a blocked link', () => {
    render(<PrdPrototypePreview pages={[{ html: '<h1>Home</h1>' }]} />);
    const frame = screen.getByTitle('页面原型') as HTMLIFrameElement;
    Object.defineProperty(frame, 'contentWindow', { value: { postMessage: vi.fn() }, configurable: true });
    act(() => { window.dispatchEvent(messageEvent({ type: 'prd-navigation-blocked', token: frameToken(frame) }, frame.contentWindow)); });
    expect(screen.getByRole('alert')).toHaveTextContent('还没有对应的原型页面');
  });
  it('ignores missing and malformed prototypes without requiring a saved version', () => {
    expect(prototypePages(null)).toEqual([]);
    expect(prototypePages({ pages: [null, {}, { mockHtml: 5 }, { mockHtml: ' ' }] })).toEqual([]);
    render(<PrdPrototypePreview pages={[]} />);
    expect(screen.queryByTitle('页面原型')).not.toBeInTheDocument();
  });
});
