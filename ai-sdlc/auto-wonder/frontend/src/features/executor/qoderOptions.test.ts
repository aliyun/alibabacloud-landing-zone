import { describe, expect, it } from 'vitest';
import {
  QODER_MODELS,
  chooseQoderModel,
  qoderProviderForClientKind,
  resolveQoderModelOptions,
} from './qoderOptions';

describe('qoder model catalog helpers', () => {
  it.each([
    ['QODER_CLI', 'qoder'],
    ['QODER_CN_CLI', 'qodercn'],
    ['CLAUDE_CODE', undefined],
    [undefined, undefined],
    // 历史数据的 clientKind 为 null 时同样不算 Qoder 系，不得解析出 provider
    [null, undefined],
  ])('maps %s to its catalog provider', (clientKind, provider) => {
    expect(qoderProviderForClientKind(clientKind)).toBe(provider);
  });

  it('uses dynamic catalog IDs and names instead of static models', () => {
    expect(resolveQoderModelOptions([
      { id: 'qoder-model-id', name: 'Qoder dynamic model' },
      { id: 'second-model-id', name: 'Second dynamic model' },
    ])).toEqual([
      { value: 'qoder-model-id', label: 'Qoder dynamic model' },
      { value: 'second-model-id', label: 'Second dynamic model' },
    ]);
  });

  it('returns every static fallback option for an empty catalog', () => {
    expect(resolveQoderModelOptions([])).toEqual(QODER_MODELS);
    expect(resolveQoderModelOptions(undefined)).toEqual(QODER_MODELS);
  });

  it('keeps a saved model when it remains in the resolved options', () => {
    const options = resolveQoderModelOptions([{ id: 'qoder-model-id', name: 'Qoder dynamic model' }]);

    expect(chooseQoderModel(options, 'qoder-model-id')).toBe('qoder-model-id');
  });

  it('chooses auto when available and otherwise the first model for stale preferences', () => {
    expect(chooseQoderModel(resolveQoderModelOptions([]), 'removed-model')).toBe('auto');
    expect(chooseQoderModel([
      { value: 'qoder-model-id', label: 'Qoder dynamic model' },
      { value: 'second-model-id', label: 'Second dynamic model' },
    ], 'removed-model')).toBe('qoder-model-id');
    expect(chooseQoderModel([], 'removed-model')).toBe('');
  });
});
