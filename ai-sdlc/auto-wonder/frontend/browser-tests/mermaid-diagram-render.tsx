import React, { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { MarkdownView } from '../src/shared/ui/MarkdownView';
import { WorkitemContent } from '../src/features/workitem/components/WorkitemContent';
import { ArtifactPreviewModal } from '../src/features/workitem/components/ArtifactPreviewModal';
import type { Artifact } from '../src/shared/types/workitem';

// Mermaid 图形浏览器 fixture：以真实浏览器验证工单正文（WorkitemContent）与
// 需求文档预览（ArtifactPreviewModal → FilePreview → MarkdownView）把 mermaid 围栏
// 渲染为 SVG，而非限制为共享层的 jsdom 断言。
// 附件预览的 /api/artifacts/5001/{preview,download} 由 run.cjs 路由打桩，mock 不替代真实部署环境。
const fence = (source: string, marker = '```') => `${marker}mermaid\n${source}\n${marker}`;

const FLOWCHART = 'flowchart TD\n  A["提交需求"] --> B["查看图形"]';
const SEQUENCE = 'sequenceDiagram\n  participant 客户端\n  participant 服务端\n  客户端->>服务端: 请求图形\n  服务端-->>客户端: 返回 SVG';
const MINDMAP = 'mindmap\n  root((工单交付))\n    需求分析\n    编码实现';
const STATE = 'stateDiagram-v2\n  [*] --> 待处理\n  待处理 --> 已完成\n  已完成 --> [*]';
const CLASS = 'classDiagram\n  class Workitem {\n    +long id\n    +render()\n  }';
const ER = 'erDiagram\n  WORK_ITEM ||--o{ ARTIFACT : contains\n  WORK_ITEM {\n    long id\n  }\n  ARTIFACT {\n    long id\n  }';
const INVALID = 'definitely-not-a-diagram\n  ???';

function buildBody(firstNode: string) {
  return [
    '## 图形验收（mock 正文）',
    fence(FLOWCHART.replace('提交需求', firstNode)),
    fence(SEQUENCE),
    fence(MINDMAP),
    fence(STATE),
    fence(CLASS),
    fence(ER),
    '```text',
    'flowchart TD',
    '  A-->B',
    '```',
    '```js',
    'const answer = 42;',
    '```',
    '行内代码：`inline-code`',
    '',
    fence(INVALID),
  ].join('\n\n');
}

function buildAttachment() {
  return [
    '# 需求文档（mock）',
    fence(FLOWCHART),
    fence(SEQUENCE),
    fence(MINDMAP),
    fence(STATE),
    fence(CLASS),
    fence(ER),
  ].join('\n\n');
}

const misc = [
  '| 图形 | 状态 |',
  '| --- | --- |',
  '| flowchart | 完成 |',
  '',
  '- [x] 已验收',
  '- [ ] 待验收',
  '',
  '链接：[文档](https://example.com/doc)',
  '',
  '提及：@淘飞',
  '',
  '行内代码：`inline-code`',
].join('\n');

const attachment: Artifact = {
  id: 5001,
  workitemId: 0,
  dispatchId: null,
  name: 'requirements.md',
  type: 'REQUIREMENT',
  size: buildAttachment().length,
  gmtCreate: '2026-01-01T00:00:00Z',
};

function App() {
  const [updated, setUpdated] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const body = useMemo(() => buildBody(updated ? '提交需求（已更新）' : '提交需求'), [updated]);
  return (
    <AppearanceProvider accent="#1677ff">
      <div data-testid="workitem-mock-fixture" style={{ maxWidth: 860, padding: 16 }}>
        <WorkitemContent title="Mermaid 工单正文（mock）" contentMd={body} readOnly />
      </div>
      <div data-testid="mermaid-markdown-fixture" style={{ maxWidth: 860, padding: 16 }}>
        <MarkdownView content={misc} mentionNames={['淘飞']} />
      </div>
      <div style={{ display: 'flex', gap: 12, padding: 16 }}>
        <button data-testid="toggle-body" onClick={() => setUpdated(value => !value)}>切换正文图形</button>
        <button data-testid="toggle-preview" onClick={() => setPreviewOpen(value => !value)}>
          {previewOpen ? '关闭附件预览' : '打开附件预览'}
        </button>
      </div>
      <ArtifactPreviewModal open={previewOpen} artifact={attachment} onClose={() => setPreviewOpen(false)} />
    </AppearanceProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <App />
  </LocaleProvider>,
);
