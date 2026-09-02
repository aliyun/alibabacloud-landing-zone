export type QoderSelectOption = { value: string; label: string };

export type QoderModelOptions = {
  contextWindows: QoderSelectOption[];
  reasoningEfforts: QoderSelectOption[];
  defaultContextWindow: string;
  defaultReasoningEffort: string;
};

export const QODER_MODELS: QoderSelectOption[] = [
  { value: 'auto', label: 'Auto (default)' },
  { value: 'ultimate', label: 'Ultimate' },
  { value: 'performance', label: 'Performance' },
  { value: 'efficient', label: 'Efficient' },
  { value: 'lite', label: 'Lite' },
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

const CONTEXT_WINDOWS: QoderSelectOption[] = [
  { value: '1000000', label: '1M' },
  { value: '400000', label: '400K' },
  { value: '260000', label: '260K' },
];

const REASONING_EFFORTS: QoderSelectOption[] = [
  { value: 'max', label: 'Max' },
  { value: 'xhigh', label: 'Extra High' },
  { value: 'high', label: 'High' },
  { value: 'medium', label: 'Medium' },
  { value: 'low', label: 'Low' },
  { value: 'none', label: 'None' },
];

const MODEL_OPTIONS: Record<string, QoderModelOptions> = Object.fromEntries(
  QODER_MODELS.map(({ value }) => [value, {
    contextWindows: CONTEXT_WINDOWS,
    reasoningEfforts: REASONING_EFFORTS,
    defaultContextWindow: '260000',
    defaultReasoningEffort: value === 'ultimate' ? 'high' : 'medium',
  }]),
);

export function qoderOptionsForModel(model: string): QoderModelOptions {
  return MODEL_OPTIONS[model] ?? MODEL_OPTIONS.auto;
}

export type QoderLaunchOptions = {
  model: string;
  contextWindow: string;
  reasoningEffort: string;
};
