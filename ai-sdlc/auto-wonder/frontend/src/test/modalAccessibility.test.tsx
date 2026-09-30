import { render, screen } from '@testing-library/react';
import { Modal } from 'antd';

it('keeps modal names distinct when a hidden preview is mounted before a confirmation', () => {
  const dialogs = (message: string) => (
    <>
      <Modal title="产物预览" open={false} forceRender><p>预览内容</p></Modal>
      <Modal title="删除工单" open><p>{message}</p></Modal>
    </>
  );
  const { rerender } = render(dialogs('确认删除'));
  const confirmation = screen.getByRole('dialog', { name: '删除工单' });
  const titleId = confirmation.getAttribute('aria-labelledby');
  expect(titleId).toBeTruthy();
  expect(Array.from(document.querySelectorAll('[id]')).filter(node => node.id === titleId)).toHaveLength(1);
  vi.restoreAllMocks();
  rerender(dialogs('正在删除'));
  expect(screen.getByRole('dialog', { name: '删除工单' })).toHaveAttribute('aria-labelledby', titleId);
});
