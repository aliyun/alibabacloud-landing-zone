export interface QoderPatPreset {
  key: 'qoder' | 'qodercn';
  name: 'QODER_PERSONAL_ACCESS_TOKEN' | 'QODERCN_PERSONAL_ACCESS_TOKEN';
  tokenUrl: string;
  labelKey: 'presetQoderPat' | 'presetQoderCnPat';
  hintKey: 'presetQoderPatHint' | 'presetQoderCnPatHint';
  descriptionKey: 'patDescription' | 'patDescriptionCn';
}

export const QODER_PAT_PRESETS: readonly QoderPatPreset[] = [
  {
    key: 'qoder',
    name: 'QODER_PERSONAL_ACCESS_TOKEN',
    tokenUrl: 'https://qoder.com/account/integrations',
    labelKey: 'presetQoderPat',
    hintKey: 'presetQoderPatHint',
    descriptionKey: 'patDescription',
  },
  {
    key: 'qodercn',
    name: 'QODERCN_PERSONAL_ACCESS_TOKEN',
    tokenUrl: 'https://qoder.cn/account/integrations',
    labelKey: 'presetQoderCnPat',
    hintKey: 'presetQoderCnPatHint',
    descriptionKey: 'patDescriptionCn',
  },
];