import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Select, Space, Tooltip } from 'antd';
import { AimOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { PresetViewport } from './PrdHtmlPreview';

export interface PrototypePage { name?: string; html: string }
/** A prototype element the user picked; the host enriches it with the page it came from. */
export interface PrototypeElement {
  tag: string;
  selector: string;
  cssPath: string;
  xpath: string;
  text?: string;
  page?: string;
}
export function prototypeDocument(html: string, token: string): string {
  const guard = `<script>(()=>{const notify=()=>parent.postMessage({type:'prd-navigation-blocked',token:${JSON.stringify(token)}},'*');document.addEventListener('click',event=>{const link=event.target.closest?.('a,area');if(!link)return;event.preventDefault();const href=link.getAttribute('href')||'';if(href.startsWith('#')&&href.length>1){const target=document.getElementById(href.slice(1));if(target){target.scrollIntoView();return;}}notify();},true);document.addEventListener('submit',event=>{event.preventDefault();notify();},true);})();</script>`;
  return '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data: blob:; style-src \'unsafe-inline\'; script-src \'unsafe-inline\'; base-uri \'none\'; form-action \'none\'">' + guard + selectionScript(token) + html;
}
// Window capture runs before the document-level guard, so picking an element never navigates.
function selectionScript(token: string): string {
  return '<script>(()=>{' +
    'const token=' + JSON.stringify(token) + ';' +
    'let enabled=false,hovered=null,saved=null;' +
    'const post=p=>{try{parent.postMessage(Object.assign({token:token},p),"*")}catch(e){}};' +
    'const text=el=>{const value=(el.innerText||el.textContent||"").replace(/\\s+/g," ").trim();return value.slice(0,80)};' +
    'const cssPath=el=>{if(el===document.body)return "body";const parts=[];' +
    'for(let node=el;node&&node.nodeType===1&&node!==document.body&&parts.length<12;node=node.parentElement){' +
    'let part=node.tagName.toLowerCase();const parent=node.parentElement;' +
    'if(parent){const same=Array.prototype.filter.call(parent.children,c=>c.tagName===node.tagName);' +
    'if(same.length>1)part+=":nth-of-type("+(same.indexOf(node)+1)+")"}parts.unshift(part)}' +
    'if(!parts.length)return el.tagName.toLowerCase();return "body > "+parts.join(" > ")};' +
    'const xpath=el=>{const parts=[];for(let node=el;node&&node.nodeType===1;node=node.parentElement){' +
    'let index=1;for(let sib=node.previousElementSibling;sib;sib=sib.previousElementSibling){if(sib.tagName===node.tagName)index++}' +
    'parts.unshift(node.tagName.toLowerCase()+"["+index+"]")}return "/"+parts.join("/")};' +
    'const selector=el=>{if(el.id)return "#"+el.id;' +
    'const names=["data-testid","data-test","aria-label"];' +
    'for(let i=0;i<names.length;i++){const value=el.getAttribute(names[i]);' +
    'if(value)return el.tagName.toLowerCase()+"["+names[i]+"=\\""+value.replace(/["\\\\]/g,"\\\\$&")+"\\"]"}' +
    'return cssPath(el)};' +
    'const clear=()=>{if(!hovered||!saved)return;hovered.style.outline=saved.outline;' +
    'hovered.style.outlineOffset=saved.offset;hovered=null;saved=null};' +
    'const highlight=el=>{if(hovered===el)return;clear();hovered=el;' +
    'saved={outline:el.style.outline,offset:el.style.outlineOffset};' +
    'el.style.outline="2px solid #1677ff";el.style.outlineOffset="2px"};' +
    'const setEnabled=value=>{enabled=!!value;document.documentElement.style.cursor=enabled?"crosshair":"";if(!enabled)clear()};' +
    'window.addEventListener("mouseover",event=>{if(!enabled||!event.target||event.target.nodeType!==1)return;highlight(event.target)},true);' +
    'window.addEventListener("click",event=>{' +
    'if(!enabled)return;const el=event.target&&event.target.nodeType===1?event.target:null;' +
    'event.preventDefault();event.stopImmediatePropagation();if(!el)return;clear();' +
    'const picked={tag:el.tagName.toLowerCase(),selector:selector(el),cssPath:cssPath(el),xpath:xpath(el),text:text(el)};' +
    'post({type:"prd-element-selected",element:picked})},true);' +
    'window.addEventListener("keydown",event=>{if(!enabled||event.key!=="Escape")return;setEnabled(false);' +
    'post({type:"prd-selection-cancel"})},true);' +
    'window.addEventListener("message",event=>{const data=event&&event.data;' +
    'if(!data||data.token!==token||data.type!=="prd-selection-mode")return;setEnabled(data.enabled)},false);' +
    '})();</script>';
}
export function prototypePages(data: unknown): PrototypePage[] {
  if (!data || typeof data !== 'object' || !('pages' in data) || !Array.isArray(data.pages)) return [];
  return data.pages.flatMap(page => page && typeof page.mockHtml === 'string' && page.mockHtml.trim()
    ? [{ name: typeof page.title === 'string' ? page.title : undefined, html: page.mockHtml }] : []);
}
export function PrdPrototypePreview({ pages, viewport = 'fit', selecting = false, onSelectingChange, onSelectElement }: {
  pages: PrototypePage[];
  viewport?: PresetViewport;
  selecting?: boolean;
  onSelectingChange?: (selecting: boolean) => void;
  onSelectElement?: (element: PrototypeElement) => void;
}) {
  const { t } = useTranslation('prd');
  const [selected, setSelected] = useState(0);
  const [reset, setReset] = useState(0);
  const [blocked, setBlocked] = useState(false);
  const frame = useRef<HTMLIFrameElement>(null);
  const loadedDocument = useRef<string | null>(null);
  const token = useMemo(() => Math.random().toString(36).slice(2), []);
  const index = selected < pages.length ? selected : 0;
  // The frame survives page and mode changes, so the receiver reads the latest props through a ref.
  const latest = useRef({ pages, index, onSelectElement, onSelectingChange });
  latest.current = { pages, index, onSelectElement, onSelectingChange };
  const syncSelectionMode = () => frame.current?.contentWindow?.postMessage({ type: 'prd-selection-mode', token, enabled: selecting }, '*');
  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow || event.data?.token !== token) return;
      if (event.data.type === 'prd-navigation-blocked') setBlocked(true);
      if (event.data.type === 'prd-selection-cancel') latest.current.onSelectingChange?.(false);
      const element = event.data.type === 'prd-element-selected' ? event.data.element : null;
      if (element && typeof element.selector === 'string' && typeof element.tag === 'string') {
        latest.current.onSelectElement?.({ ...element, page: latest.current.pages[latest.current.index]?.name });
        latest.current.onSelectingChange?.(false);
      }
    };
    window.addEventListener('message', receive);
    return () => window.removeEventListener('message', receive);
  }, [token]);
  useEffect(() => { syncSelectionMode(); }, [selecting, index, reset, token]);
  if (!pages.length) return null;
  const document = prototypeDocument(pages[index].html, token);
  const selectLabel = selecting ? t('preview.cancelSelect') : t('preview.selectElement');
  return <>
    <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
    {pages.length > 1 && <Select aria-label={t('preview.page')} value={index} onChange={setSelected}
      style={{ minWidth: 160, marginBottom: 8 }}
      options={pages.map((page, value) => ({ value, label: page.name || t('preview.pageNumber', { number: value + 1 }) }))} />}
    {onSelectingChange && <Tooltip title={selectLabel}><Button type={selecting ? 'primary' : 'text'} size="small" aria-label={selectLabel}
      aria-pressed={selecting} icon={<AimOutlined />} onClick={() => onSelectingChange(!selecting)} /></Tooltip>}
    <Tooltip title={t('preview.reset')}><Button type="text" size="small" aria-label={t('preview.reset')} icon={<ReloadOutlined />} onClick={() => { loadedDocument.current = null; setReset(value => value + 1); setBlocked(false); }} /></Tooltip>
    </Space>
    {selecting && <Alert type="info" showIcon closable onClose={() => onSelectingChange?.(false)} message={t('preview.selectHint')} style={{ marginBottom: 8 }} />}
    {blocked && <Alert type="info" showIcon closable onClose={() => setBlocked(false)} message={t('preview.navigationBlocked')} description={t('preview.navigationHint')} style={{ marginBottom: 8 }} />}
    <div style={{ width: '100%', overflow: 'auto' }}>
      <iframe ref={frame} key={`${index}:${reset}`} title={t('preview.prototype')} sandbox="allow-scripts" referrerPolicy="no-referrer"
        onLoad={() => {
          if (loadedDocument.current === document) {
            loadedDocument.current = null; setBlocked(true); setReset(value => value + 1);
          } else { loadedDocument.current = document; syncSelectionMode(); }
        }}
        style={{ width: viewport === 'mobile' ? 390 : viewport === 'desktop' ? 1280 : '100%', height: '75vh', border: 0, display: 'block', marginInline: 'auto' }}
        srcDoc={document} />
    </div>
  </>;
}
