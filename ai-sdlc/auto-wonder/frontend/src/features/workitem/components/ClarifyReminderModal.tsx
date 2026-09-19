import { useEffect, useState } from 'react';
import { Button, Checkbox, Modal } from 'antd';

interface ClarifyReminderModalProps {
  open: boolean;
  /** 「确认，去澄清」：跳转到本工单的 AI 需求澄清。rememberChoice 是「下次不再提醒」的勾选态。 */
  onConfirm: (rememberChoice: boolean) => void;
  /** 「跳过」：关闭提醒并继续原有的启动交付流程。rememberChoice 是「下次不再提醒」的勾选态。 */
  onSkip: (rememberChoice: boolean) => void;
  /** 关闭图标 / 遮罩 / Esc：只关提醒，不进入启动交付流程。 */
  onClose: () => void;
}

const REMINDER_TEXT = '建议先完成 AI 需求澄清，以提升后续交付质量。也可以选择跳过，直接启动交付。';
const REMEMBER_TEXT = '记住我的选项，下次不再提醒';

/**
 * 「启动交付」前的需求澄清引导（工单 53315），带「下次不再提醒」降噪选项（工单 54819）。
 *
 * 跳过与关闭是两条不同的路径：关闭图标只表达「我先不决定」，
 * 不该顺手把启动交付弹窗打开，所以 footer 用自定义按钮而不是 okText/cancelText。
 * 同理，勾选态只在跳过或去澄清时交给上层落盘，关闭图标不带走它。
 */
export function ClarifyReminderModal({ open, onConfirm, onSkip, onClose }: ClarifyReminderModalProps) {
  const [rememberChoice, setRememberChoice] = useState(false);

  // 每次重新弹出都回到未勾选：上一次用关闭图标退出时偏好并没有落盘，
  // 留着勾选态会让用户以为已经记住了。
  useEffect(() => {
    if (open) setRememberChoice(false);
  }, [open]);

  return (
    <Modal
      title="建议先完成需求澄清"
      open={open}
      onCancel={onClose}
      destroyOnHidden
      footer={[
        <Button key="skip" onClick={() => onSkip(rememberChoice)}>
          跳过
        </Button>,
        <Button key="confirm" type="primary" onClick={() => onConfirm(rememberChoice)}>
          确认，去澄清
        </Button>,
      ]}
    >
      {REMINDER_TEXT}
      <Checkbox
        checked={rememberChoice}
        onChange={(event) => setRememberChoice(event.target.checked)}
        style={{ display: 'flex', alignItems: 'center', marginTop: 16 }}
      >
        {REMEMBER_TEXT}
      </Checkbox>
    </Modal>
  );
}
