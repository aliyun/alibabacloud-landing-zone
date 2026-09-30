import { describe, expect, it } from 'vitest';
import { redactDiagnostic } from './commentActivity';

describe('provider diagnostic redaction', () => {
  it('preserves error codes, newlines and readable paths while hiding credentials', () => {
    const error = 'qoder response timeout\nexit=1 path=/tmp/qoder/log\nAuthorization: Bearer abc123\n{"api_key":"example-secret"}\nhttps://example.test?token=private-value&code=429';
    const result = redactDiagnostic(error);
    expect(result).toContain('qoder response timeout\nexit=1 path=/tmp/qoder/log');
    expect(result).toContain('code=429');
    for (const secret of ['abc123', 'example-secret', 'private-value']) expect(result).not.toContain(secret);
  });
});
