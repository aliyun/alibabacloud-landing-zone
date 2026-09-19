import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { listAllSkills, type Skill } from './api';

function pageSkill(id: number): Skill {
  return {
    id,
    name: `能力 ${id}`,
    type: 'SKILL',
    installSpec: 'built-in',
    description: '',
    version: 1,
    gmtCreate: '2026-07-01',
  };
}

describe('listAllSkills', () => {
  it('fetches every page until the backend total is covered', async () => {
    const requestedPages: number[] = [];
    server.use(
      http.get('/api/skills', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
        const size = Number(new URL(request.url).searchParams.get('size') ?? '0');
        requestedPages.push(page);
        const count = Math.max(0, Math.min(size, 250 - (page - 1) * size));
        const list = Array.from({ length: count }, (_, i) => pageSkill((page - 1) * size + i + 1));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list, total: 250, pageNum: page, pageSize: size },
        });
      }),
    );

    const all = await listAllSkills();

    expect(requestedPages).toEqual([1, 2, 3]);
    expect(all).toHaveLength(250);
    expect(all[0].id).toBe(1);
    expect(all[249].id).toBe(250);
  });

  it('includes skills beyond 50 pages without silently truncating groups', async () => {
    const requestedPages: number[] = [];
    server.use(
      http.get('/api/skills', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
        requestedPages.push(page);
        const list = Array.from({ length: 100 }, (_, i) => pageSkill((page - 1) * 100 + i + 1));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list, total: 5100, pageNum: page, pageSize: 100 },
        });
      }),
    );

    const all = await listAllSkills();

    expect(requestedPages).toHaveLength(51);
    expect(all).toHaveLength(5100);
  });

  it('forwards the type filter to the backend', async () => {
    let requestedType: string | null = null;
    server.use(
      http.get('/api/skills', ({ request }) => {
        requestedType = new URL(request.url).searchParams.get('type');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [], total: 0, pageNum: 1, pageSize: 100 },
        });
      }),
    );

    await listAllSkills('MCP');

    expect(requestedType).toBe('MCP');
  });
});
