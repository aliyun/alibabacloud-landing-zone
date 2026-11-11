import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  // Java deployments replace the entire asset set. Keep all executable code and
  // styles in the initial load so open tabs never need retired release chunks.
  // Measured 2026-11-05: entry JS gzip ~1.75 -> ~2.97 MB (+1.22 MB); all JS
  // stays ~2.98 MB. This trades first-load cost for uninterrupted later navigation.
  // Restore splitting only with cross-release asset retention; see docs/frontend-deployment.md.
  build: {
    cssCodeSplit: false,
    rollupOptions: { output: { inlineDynamicImports: true } },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: process.env.AW_LOCAL_API_URL || 'http://localhost:7001',
        changeOrigin: true,
      },
      '/ws': {
        target: (process.env.AW_LOCAL_API_URL || 'http://localhost:7001').replace(/^http/, 'ws'),
        ws: true,
      },
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 10000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      // 本仓基线存在既有失败用例，默认 false 会让覆盖率报告永远不落盘。
      reportOnFailure: true,
      // 不设全局阈值：存量覆盖率未知，全局门槛会让构建直接红掉。
      // 增量覆盖率靠 `npm run test:coverage` 的报告逐文件核对。
      exclude: [
        'src/test/**',
        '**/*.test.ts',
        '**/*.test.tsx',
        '**/*.d.ts',
        '**/main.tsx',
      ],
    },
  },
});
