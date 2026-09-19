import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ClarifyReminderModal } from './ClarifyReminderModal';

type ModalProps = Partial<Parameters<typeof ClarifyReminderModal>[0]>;

function renderModal(props: ModalProps = {}) {
  const onConfirm = vi.fn();
  const onSkip = vi.fn();
  const onClose = vi.fn();
  const view = render(
    <ClarifyReminderModal open onConfirm={onConfirm} onSkip={onSkip} onClose={onClose} {...props} />,
  );
  return { ...view, onConfirm, onSkip, onClose };
}

/** antd Modal 渲染在 body 上的 portal 里，container 拿不到，只能从 baseElement 找关闭图标。 */
function clickCloseIcon(baseElement: HTMLElement) {
  const closeIcon = baseElement.querySelector('.ant-modal-close');
  expect(closeIcon).not.toBeNull();
  return userEvent.click(closeIcon as Element);
}

/** antd 默认 autoInsertSpace 会在两个中文字符之间插空格，「跳过」的可访问名实际是 "跳 过"。 */
const SKIP_BUTTON = /^跳\s*过$/;
const CONFIRM_BUTTON = '确认，去澄清';
/** 用部分匹配的可访问名：既锁定是 checkbox，又不被 antd 对中文文案的空白处理影响。 */
const REMEMBER_CHECKBOX = { name: /记住我的选项/ };

function rememberCheckbox() {
  return screen.getByRole('checkbox', REMEMBER_CHECKBOX);
}

describe('ClarifyReminderModal', () => {
  it('renders nothing while closed', () => {
    render(
      <ClarifyReminderModal open={false} onConfirm={vi.fn()} onSkip={vi.fn()} onClose={vi.fn()} />,
    );

    expect(screen.queryByText('建议先完成需求澄清')).toBeNull();
    expect(screen.queryByRole('button', { name: SKIP_BUTTON })).toBeNull();
    expect(screen.queryByRole('checkbox', REMEMBER_CHECKBOX)).toBeNull();
  });

  it('suggests finishing clarification and offers both skip and confirm', () => {
    renderModal();

    expect(screen.getByText('建议先完成需求澄清')).toBeInTheDocument();
    expect(screen.getByText(/建议先完成 AI 需求澄清，以提升后续交付质量/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: SKIP_BUTTON })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: CONFIRM_BUTTON })).toBeInTheDocument();
  });

  it('offers an unticked 下次不再提醒 opt-out (工单 54819)', () => {
    renderModal();

    // 默认不勾选：降噪是用户主动选的，不能替他决定以后都不提醒
    expect(rememberCheckbox()).toBeInTheDocument();
    expect(rememberCheckbox()).toHaveAccessibleName('记住我的选项，下次不再提醒');
    expect(rememberCheckbox()).not.toBeChecked();
  });

  it('goes to clarification on confirm without touching the other two paths', async () => {
    const { onConfirm, onSkip, onClose } = renderModal();

    await userEvent.click(screen.getByRole('button', { name: CONFIRM_BUTTON }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith(false);
    expect(onSkip).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('hands over to the original start-delivery flow on skip', async () => {
    const { onSkip, onConfirm, onClose } = renderModal();

    await userEvent.click(screen.getByRole('button', { name: SKIP_BUTTON }));

    expect(onSkip).toHaveBeenCalledTimes(1);
    expect(onSkip).toHaveBeenCalledWith(false);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('only dismisses itself through the close icon, never skipping into delivery', async () => {
    const { baseElement, onClose, onSkip, onConfirm } = renderModal();

    await clickCloseIcon(baseElement);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onSkip).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('reports the opt-out on skip so the caller can persist it', async () => {
    const { onSkip, onConfirm } = renderModal();

    await userEvent.click(rememberCheckbox());
    expect(rememberCheckbox()).toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: SKIP_BUTTON }));

    expect(onSkip).toHaveBeenCalledWith(true);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('reports the opt-out on confirm so the caller can persist it', async () => {
    const { onConfirm, onSkip } = renderModal();

    await userEvent.click(rememberCheckbox());
    await userEvent.click(screen.getByRole('button', { name: CONFIRM_BUTTON }));

    expect(onConfirm).toHaveBeenCalledWith(true);
    expect(onSkip).not.toHaveBeenCalled();
  });

  it('lets the opt-out be unticked again before choosing', async () => {
    const { onSkip } = renderModal();

    await userEvent.click(rememberCheckbox());
    await userEvent.click(rememberCheckbox());
    expect(rememberCheckbox()).not.toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: SKIP_BUTTON }));

    expect(onSkip).toHaveBeenCalledWith(false);
  });

  it('does not carry the opt-out through the close icon', async () => {
    const { baseElement, onClose, onSkip, onConfirm } = renderModal();

    await userEvent.click(rememberCheckbox());
    await clickCloseIcon(baseElement);

    // 关闭图标只代表「我先不决定」，勾了也不该被当成一次决定上报
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onSkip).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('starts unticked again when the reminder is reopened', async () => {
    const onConfirm = vi.fn();
    const onSkip = vi.fn();
    const onClose = vi.fn();
    const { rerender } = render(
      <ClarifyReminderModal open onConfirm={onConfirm} onSkip={onSkip} onClose={onClose} />,
    );

    await userEvent.click(rememberCheckbox());
    expect(rememberCheckbox()).toBeChecked();

    rerender(<ClarifyReminderModal open={false} onConfirm={onConfirm} onSkip={onSkip} onClose={onClose} />);
    rerender(<ClarifyReminderModal open onConfirm={onConfirm} onSkip={onSkip} onClose={onClose} />);

    // 上一次是用关闭图标退出的，偏好没落盘，重开时不该留着勾选态误导用户
    expect(rememberCheckbox()).not.toBeChecked();
    expect(onSkip).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
