import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import { getUnifiedTimeline } from './api';

describe('timeline window pagination', () => {
  it.each([
    { count: 45, header: 'true', hasMore: true },
    { count: 0, header: 'true', hasMore: true },
    { count: 50, header: 'false', hasMore: false },
    { count: 51, header: undefined, hasMore: true },
  ])('uses the server continuation flag: $count rows, $header', async ({ count, header, hasMore }) => {
    let page: string | null = null;
    const items = Array.from({ length: count }, (_, id) => ({ id }));
    server.use(http.get('/api/workitems/42/unified-timeline/window', ({ request }) => {
      page = new URL(request.url).searchParams.get('page');
      return HttpResponse.json({ success: true, code: '0', data: items }, {
        headers: header === undefined ? {} : { 'X-Timeline-Has-More': header },
      });
    }));
    expect(await getUnifiedTimeline(42, 2)).toEqual({ items, hasMore });
    expect(page).toBe('2');
  });
});
