module.exports = {
  root: true,
  env: { browser: true, es2020: true },
  extends: ['eslint:recommended', 'plugin:@typescript-eslint/recommended', 'plugin:react-hooks/recommended'],
  ignorePatterns: ['dist'],
  parser: '@typescript-eslint/parser',
  plugins: ['react-hooks'],
  rules: {
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
  },
  overrides: [
    {
      files: ['src/**/*.{ts,tsx}'],
      excludedFiles: ['src/shared/lib/clipboard.ts'],
      rules: {
        'no-restricted-properties': ['error', {
          object: 'navigator',
          property: 'clipboard',
          message: 'Use copyTextToClipboard so plaintext deployments retain a fallback.',
        }],
      },
    },
  ],
};
