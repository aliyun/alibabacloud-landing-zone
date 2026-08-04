import { describe, expect, it } from 'vitest';

const productionModules = import.meta.glob([
  '/src/**/*.ts',
  '/src/**/*.tsx',
  '!/src/**/*.test.ts',
  '!/src/**/*.test.tsx',
  '!/src/test/**',
], {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>;

describe('organization access source guard', () => {
  it('does not restore legacy permission gates or role APIs', () => {
    const forbidden = [
      /\bPermGate\b/,
      /\busePermissions?\b/,
      /\bhasPermission\b/,
      /\/api\/roles\b/,
      /\/api\/permissions\b/,
      /\bperm\s*:/,
    ];

    for (const [path, source] of Object.entries(productionModules)) {
      for (const pattern of forbidden) {
        expect(source, `${path} contains ${pattern}`).not.toMatch(pattern);
      }
    }
  });

  it('does not hide or disable controls based on organization access level', () => {
    const accessControlledPresentation = [
      /accessLevel[^\n]{0,120}\b(?:disabled|hidden)\b/,
      /\b(?:disabled|hidden)\b[^\n]{0,120}accessLevel/,
      /allows\([^\n]+\)[^\n]{0,80}\?\s*(?:null|false)\b/,
    ];

    for (const [path, source] of Object.entries(productionModules)) {
      for (const pattern of accessControlledPresentation) {
        expect(source, `${path} contains ${pattern}`).not.toMatch(pattern);
      }
    }
  });

  it('does not confirm a mutation without rechecking access', () => {
    const unguardedConfirmation =
      /onConfirm=\{(?:(?!accessCommand|runAccessCommand|runWithAccess)[\s\S]){0,320}?\.mutate(?:Async)?\(/;

    for (const [path, source] of Object.entries(productionModules)) {
      expect(source, `${path} confirms a mutation without an access command`)
        .not.toMatch(unguardedConfirmation);
    }
  });
});
