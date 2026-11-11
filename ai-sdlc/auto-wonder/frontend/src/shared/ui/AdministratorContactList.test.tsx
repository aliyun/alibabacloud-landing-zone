import { render, screen } from '@testing-library/react';
import { AdministratorContactList } from './AdministratorContactList';

describe('AdministratorContactList', () => {
  it('renders one list item per administrator with nickname and email on the same row', () => {
    render(
      <AdministratorContactList
        title="平台管理员"
        emptyText="暂无可用平台管理员"
        contacts={[
          { nickname: '周宁', email: 'zhou@example.com' },
          { nickname: 'Alex', email: 'alex@example.com' },
        ]}
      />,
    );

    expect(screen.getByText('平台管理员')).toBeInTheDocument();
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('周宁');
    expect(items[0]).toHaveTextContent('zhou@example.com');
    expect(items[1]).toHaveTextContent('Alex');
  });

  it('only renders fields with a value and falls back to the generic hint when empty', () => {
    const { rerender } = render(
      <AdministratorContactList
        title="平台管理员"
        emptyText="暂无可用平台管理员"
        contacts={[{ nickname: 'OnlyName', email: null }, { nickname: null, email: 'only@example.com' }]}
      />,
    );
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('OnlyName');
    expect(items[1]).toHaveTextContent('only@example.com');

    rerender(
      <AdministratorContactList title="平台管理员" emptyText="暂无可用平台管理员" contacts={[]} />,
    );
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.getByText('暂无可用平台管理员')).toBeInTheDocument();
  });
});
