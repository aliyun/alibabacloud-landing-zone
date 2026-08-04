import type { ComponentType } from 'react';
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
    label: '钉钉',
    enabled: true,
    Panel: DingTalkBindingPanel,
  },
  {
    key: 'FEISHU',
    label: '飞书（待接入）',
    enabled: false,
    Panel: null,
  },
  {
    key: 'SLACK',
    label: 'Slack（待接入）',
    enabled: false,
    Panel: null,
  },
];
