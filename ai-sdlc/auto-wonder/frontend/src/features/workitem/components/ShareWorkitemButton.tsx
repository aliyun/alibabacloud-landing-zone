import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Tooltip, message } from 'antd';
import { CheckOutlined, ShareAltOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { BRANDING_QUERY_KEY, getPublicBranding } from '@/features/platform/brandingApi';
import { resolvePlatformBaseUrl } from '@/features/platform/platformBaseUrl';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { buildWorkitemShareText } from '../shareLink';

/** ✓ 反馈停留时长；与 clarification/CopyMessageButton 保持一致 */
export const SHARE_FEEDBACK_MS = 1500;

/** 工单要求的浅灰：比正文弱一档，不与右侧操作区抢注意力 */
const SHARE_COLOR = '#8c8c8c';

interface ShareWorkitemButtonProps {
  workitemId: number;
  title: string;
}

/** 详情页右上角的分享入口：单击即复制「链接 《标题》」，图标瞬变 ✓。
 *  刻意不弹成功 message——✓ 加「已复制」本身就是反馈，失败才需要 message。 */
export function ShareWorkitemButton({ workitemId, title }: ShareWorkitemButtonProps) {
  const { data: branding } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    // StrictMode 会挂载→卸载→再挂载一次：不在这里复位，开发态下 ref 会永远停在
    // false，复制成功也不再切 ✓。
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const handleShare = useCallback(async () => {
    const text = buildWorkitemShareText(resolvePlatformBaseUrl(branding), workitemId, title);
    const ok = await copyTextToClipboard(text);
    if (!ok) {
      message.error('复制失败，请检查浏览器剪贴板权限');
      return;
    }
    // await 期间可能已卸载（比如从详情页返回列表），此时清理函数早跑过了，
    // 再挂计时器就没人清得掉它——直接收手。
    if (!mountedRef.current) return;
    setCopied(true);
    // 连续点击时重置计时，而不是让旧计时提前把 ✓ 收回
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setCopied(false), SHARE_FEEDBACK_MS);
  }, [branding, workitemId, title]);

  const label = copied ? '已复制' : '分享';

  return (
    <Tooltip title={copied ? '链接已复制到剪贴板' : '复制工单链接'}>
      <Button
        data-testid="workitem-share-button"
        type="text"
        size="small"
        icon={copied ? <CheckOutlined /> : <ShareAltOutlined />}
        // 图标自带 aria-label="share-alt"，不显式命名的话可访问名会变成「share-alt 分享」
        aria-label={label}
        style={{ color: SHARE_COLOR, fontSize: 13 }}
        onClick={handleShare}
      >
        {label}
      </Button>
    </Tooltip>
  );
}
