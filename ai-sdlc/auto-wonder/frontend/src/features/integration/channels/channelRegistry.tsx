import { i18n } from '@/shared/i18n';
import type { ComponentType } from 'react';
import { FeishuBindingPanel } from './feishu/FeishuBindingPanel';
import { DingTalkBindingPanel } from './dingtalk/DingTalkBindingPanel';

export interface ChannelDescriptor {
  key: string;
  label: string;
  enabled: boolean;
  Panel: ComponentType | null;
}

// 多渠道扩展缝:新增渠道 = 这里加一项 + 实现对应 Panel。导航与页面壳不变。
export const CHANNEL_REGISTRY: ChannelDescriptor[] = [
  {
    key: 'DINGTALK',
    get label() { return i18n.t('integration:dingtalk'); },
    enabled: true,
    Panel: DingTalkBindingPanel,
  },
  {
    key: 'FEISHU',
    get label() { return i18n.t('integration:feishu'); },
    enabled: true,
    Panel: FeishuBindingPanel,
  },
  {
    key: 'SLACK',
    get label() { return i18n.t('integration:slackPending'); },
    enabled: false,
    Panel: null,
  },
];
