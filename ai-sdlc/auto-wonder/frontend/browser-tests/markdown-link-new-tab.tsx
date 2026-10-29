import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { MarkdownView } from '../src/shared/ui/MarkdownView';
import { WorkitemContent } from '../src/features/workitem/components/WorkitemContent';
import { UnifiedTimeline } from '../src/features/workitem/components/UnifiedTimeline';
import type { TimelineItem } from '../src/shared/types/workitem';

// MarkdownView 链接与图片浏览器 fixture：真实浏览器验证五类链接的
// target=_blank / rel=noopener noreferrer 属性与真实点击新标签行为。
// 无后端/DB：共享组件 + mock 工单页（评论链路与正文）。
// 按真人指示（评论 144859）以 mock 工单数据承接 AC2/AC3 自验收：
// 评论卡片（仿 #55853 的 MR 链接+裸 URL）与正文各真实点击验证新标签打开且原页保持。
const content = [
  '显式链接：[文档](https://example.com/doc)',
  '',
  '裸 URL：https://example.com/bare',
  '',
  '相对链接：[相对](/some/relative/path)',
  '',
  '邮箱链接：[邮件](mailto:someone@example.com)',
  '',
  '原始 HTML：<a href="https://example.com/raw" target="_blank">原始</a>',
  '',
  '代码中的 URL 不是链接：`https://example.com/code`',
  '',
  '```',
  'https://example.com/fence',
  '```',
].join('\n');

const workitemBodyMd = [
  '正文说明：[正文文档](https://example.com/body)',
  '',
  '正文裸 URL：https://example.com/body-bare',
  '',
  '<article><p><img src="https://workitems.example.test/v2/api/workitem/adapter/file/url?fileIdentifier=fixture%2Fone.png" alt="Namespace screenshot 1" width="1600" height="800" onerror="window.markdownImageUnsafe=true" /></p><p><img src="https://workitems.example.test/v2/api/workitem/adapter/file/url?fileIdentifier=fixture%2Ftwo.png" alt="Namespace screenshot 2" width="1600" height="800" /></p></article>',
].join('\n');

const commentItem: TimelineItem = {
  id: 9001,
  type: 'comment',
  authorId: 10000,
  authorName: '淘飞',
  authorType: 'HUMAN',
  isAgent: false,
  content: [
    '交付完成：[MR !973](https://example.com/mr/973)',
    '',
    '部署说明：https://example.com/comment-bare',
    '',
    '![Comment screenshot](https://example.com/comment-image.png)',
  ].join('\n'),
  gmtCreate: new Date().toISOString(),
};

const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <div data-testid="markdown-links-fixture">
          <MarkdownView content={content} />
        </div>
        <div data-testid="workitem-mock-fixture" style={{ marginTop: 24, maxWidth: 860 }}>
          <WorkitemContent title="工单标题（mock）" contentMd={workitemBodyMd} readOnly />
          <UnifiedTimeline items={[commentItem]} />
        </div>
      </MemoryRouter>
    </QueryClientProvider>
  </LocaleProvider>,
);
