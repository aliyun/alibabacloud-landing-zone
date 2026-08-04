import { describe, it, expect } from 'vitest';
import { rendererRegistry } from './registry';

describe('rendererRegistry', () => {
  it('maps structured scenes to components', () => {
    expect(rendererRegistry.REPO_SCAN).toBeTypeOf('function');
    expect(rendererRegistry.MEMORY_IMPORT).toBeTypeOf('function');
    expect(rendererRegistry.SDLC_GEN).toBeTypeOf('function');
    expect(rendererRegistry.AGENT_CONFIG_GEN).toBeTypeOf('function');
  });

  it('returns undefined for unregistered scenes', () => {
    expect(rendererRegistry.CLARIFICATION).toBeUndefined();
  });
});
