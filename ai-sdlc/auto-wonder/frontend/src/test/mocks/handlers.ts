import { http, HttpResponse } from 'msw';

export const handlers = [
  http.get('/api/platform/branding/public', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        platformName: 'AutoWonder',
        logoUrl: '/logo.png',
        themeKey: 'aliyun-orange',
        primaryColor: '#f97316',
        domain: 'https://community.example',
        mcpBaseUrl: 'https://community.example/api/mcp',
        recommendedRuntimeVersion: '0.2.125',
        deploymentVersion: 'x.x.x',
        canManage: false,
      },
      traceId: 'trace-branding',
    });
  }),
  http.get('/api/platform/branding', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        platformName: 'AutoWonder',
        logoUrl: '/logo.png',
        themeKey: 'aliyun-orange',
        primaryColor: '#f97316',
        domain: 'https://community.example',
        mcpBaseUrl: 'https://community.example/api/mcp',
        canManage: false,
      },
      traceId: 'trace-branding-admin',
    });
  }),
  http.get('/api/platform/branding/capability', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { canManage: false },
      traceId: 'trace-branding-capability',
    });
  }),
  http.get('/api/agents/reviews/count', () => HttpResponse.json({
    success: true, code: '0', message: '', data: 0, traceId: 'trace-agent-review-count',
  })),
  http.get('/api/memories/reviews/count', () => HttpResponse.json({
    success: true, code: '0', message: '', data: 0, traceId: 'trace-memory-review-count',
  })),
  http.post('/api/auth/login', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        userId: 1,
        accessToken: 'test-access',
        refreshToken: 'test-refresh',
        user: {
          id: 1,
          username: 'test-user',
          nickname: '测试用户',
          email: 'test@example.com',
        },
      },
      traceId: 'trace-1',
    });
  }),
  http.get('/api/workspaces/mine', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-workspaces-mine',
    });
  }),
  http.get('/api/agents/reviews/count', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: 0,
      traceId: 'trace-agents-reviews-count',
    });
  }),
  http.get('/api/memories/reviews/count', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: 0,
      traceId: 'trace-memories-reviews-count',
    });
  }),
];
