import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkitemTagList } from './WorkitemTagList';

describe('WorkitemTagList', () => {
  it('renders every tag when the list fits', () => {
    render(<WorkitemTagList tags={['发布', '线上']} />);
    expect(screen.getByText('发布')).toBeInTheDocument();
    expect(screen.getByText('线上')).toBeInTheDocument();
    expect(screen.queryByTestId('workitem-tags-overflow')).not.toBeInTheDocument();
  });

  it('collapses the overflow into a +N tag with the full tag list as tooltip', async () => {
    const user = userEvent.setup();
    render(<WorkitemTagList tags={['a', 'b', 'c', 'd']} maxVisible={2} />);
    expect(screen.queryByText('c')).not.toBeInTheDocument();
    expect(screen.getByTestId('workitem-tags-overflow')).toHaveTextContent('+2');
    await user.hover(screen.getByTestId('workitem-tags-overflow'));
    expect(await screen.findByText('a、b、c、d')).toBeInTheDocument();
  });

  it('renders every tag when maxVisible is omitted', () => {
    const tags = Array.from({ length: 12 }, (_, index) => `标签${index}`);
    render(<WorkitemTagList tags={tags} />);
    tags.forEach((tag) => expect(screen.getByText(tag)).toBeInTheDocument());
    expect(screen.queryByTestId('workitem-tags-overflow')).not.toBeInTheDocument();
  });

  it('exposes the overflow tag to the keyboard with the complete list as tooltip', async () => {
    const user = userEvent.setup();
    render(<WorkitemTagList tags={['a', 'b', 'c']} maxVisible={1} />);
    const overflow = screen.getByTestId('workitem-tags-overflow');
    expect(overflow).toHaveAttribute('tabindex', '0');
    expect(overflow).toHaveAttribute('aria-label', '已隐藏 2 个标签，完整标签：a、b、c');
    await user.tab();
    expect(overflow).toHaveFocus();
    expect(await screen.findByText('a、b、c')).toBeInTheDocument();
  });

  it('hides all tags behind the overflow counter when maxVisible is zero', () => {
    render(<WorkitemTagList tags={['a', 'b']} maxVisible={0} />);
    expect(screen.queryByText('a')).not.toBeInTheDocument();
    expect(screen.getByTestId('workitem-tags-overflow')).toHaveTextContent('+2');
  });

  it('renders nothing for an empty list unless the empty state is requested', () => {
    const { container } = render(<WorkitemTagList tags={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('shows the empty hint when requested', () => {
    render(<WorkitemTagList tags={null} showEmpty />);
    expect(screen.getByText('暂无标签')).toBeInTheDocument();
  });

  it('renders a custom empty placeholder when requested', () => {
    render(<WorkitemTagList tags={[]} showEmpty emptyText="-" />);
    expect(screen.getByText('-')).toBeInTheDocument();
    expect(screen.queryByText('暂无标签')).not.toBeInTheDocument();
  });
});
