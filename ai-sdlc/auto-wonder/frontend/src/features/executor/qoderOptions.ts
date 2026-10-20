import { i18n } from '@/shared/i18n';
import type { ExecutorModelCatalogModel, ExecutorModelCatalogProvider } from './api';

export type QoderSelectOption = { value: string; label: string };

export type QoderModelOptions = {
  contextWindows: QoderSelectOption[];
  reasoningEfforts: QoderSelectOption[];
  defaultContextWindow: string;
  defaultReasoningEffort: string;
};

const QODER_MODELS: { value: string; label?: string; key?: 'modelAuto' | 'modelUltimate' | 'modelPerformance' | 'modelEfficient' | 'modelLite' }[] = [
  { value: 'auto', key: 'modelAuto' },
  { value: 'ultimate', key: 'modelUltimate' },
  { value: 'performance', key: 'modelPerformance' },
  { value: 'efficient', key: 'modelEfficient' },
  { value: 'lite', key: 'modelLite' },
  { value: 'qmodel_38max', label: 'Qwen3.8-Max' },
  { value: 'qfmodel', label: 'Qwen3.8-Flash' },
  { value: 'qmodel_latest', label: 'Qwen3.7-Max' },
  { value: 'qmodel', label: 'Qwen3.7-Plus' },
  { value: 'kmodel_latest', label: 'Kimi-K3' },
  { value: 'kmodel', label: 'Kimi-K2.7-Code' },
  { value: 'gmodel', label: 'GLM-5.3' },
  { value: 'gfmodel', label: 'GLM-5.3-Flash' },
  { value: 'dmodel', label: 'DeepSeek-V4-Pro' },
  { value: 'dfmodel', label: 'DeepSeek-V4-Flash' },
  { value: 'mmodel', label: 'MiniMax-M3' },
];

export function qoderProviderForClientKind(kind?: string | null): ExecutorModelCatalogProvider | undefined {
  if (kind === 'QODER_CLI') return 'qoder';
  if (kind === 'QODER_CN_CLI') return 'qodercn';
  return undefined;
}

export function resolveQoderModelOptions(models?: ExecutorModelCatalogModel[] | null): QoderSelectOption[] {
  return models?.length
    ? models.map(({ id, name }) => ({ value: id, label: name }))
    : QODER_MODELS.map(({ value, label, key }) => ({ value, label: key ? i18n.t(`executor:${key}`) : label! }));
}

export function chooseQoderModel(options: QoderSelectOption[], saved?: string): string {
  if (saved && options.some((option) => option.value === saved)) return saved;
  return options.find((option) => option.value === 'auto')?.value ?? options[0]?.value ?? '';
}

const CONTEXT_WINDOWS: QoderSelectOption[] = [
  { value: '1000000', label: '1M' },
  { value: '400000', label: '400K' },
  { value: '260000', label: '260K' },
];

export function qoderOptionsForModel(model: string): QoderModelOptions {
  return {
    contextWindows: CONTEXT_WINDOWS,
    reasoningEfforts: [
      { value: 'max', label: i18n.t('executor:reasonMax') },
      { value: 'xhigh', label: i18n.t('executor:reasonExtraHigh') },
      { value: 'high', label: i18n.t('executor:reasonHigh') },
      { value: 'medium', label: i18n.t('executor:reasonMedium') },
      { value: 'low', label: i18n.t('executor:reasonLow') },
      { value: 'none', label: i18n.t('executor:reasonNone') },
    ],
    defaultContextWindow: '260000',
    defaultReasoningEffort: model === 'ultimate' ? 'high' : 'medium',
  };
}

export type QoderLaunchOptions = {
  model: string;
  contextWindow: string;
  reasoningEffort: string;
};

export function resolveQoderLaunchOptions(model: string, current: Partial<QoderLaunchOptions>): QoderLaunchOptions {
  const options = qoderOptionsForModel(model);
  return {
    model,
    reasoningEffort: options.reasoningEfforts.some(({ value }) => value === current.reasoningEffort)
      ? current.reasoningEffort! : options.defaultReasoningEffort,
    contextWindow: options.contextWindows.some(({ value }) => value === current.contextWindow)
      ? current.contextWindow! : options.defaultContextWindow,
  };
}
