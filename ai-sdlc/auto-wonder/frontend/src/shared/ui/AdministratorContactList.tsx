import { Typography } from 'antd';
import type { AdministratorContact } from '@/shared/types/administrator';

const { Text } = Typography;

interface Props {
  contacts: AdministratorContact[];
  title: string;
  emptyText: string;
}

/**
 * 注册待审、待审登录与创建空间受限三处共用的联系人列表：
 * 每位管理员单独一项，昵称与邮箱同行；超出视口时列表区域内滚动。
 */
export function AdministratorContactList({ contacts, title, emptyText }: Props) {
  if (contacts.length === 0) {
    return <Text type="secondary">{emptyText}</Text>;
  }
  return (
    <div>
      <Text strong>{title}</Text>
      <ul
        data-testid="administrator-contact-list"
        style={{
          maxHeight: '35vh',
          overflowY: 'auto',
          paddingLeft: 22,
          margin: '8px 0 0',
        }}
      >
        {contacts.map((contact, index) => (
          <li key={`${contact.email ?? contact.nickname ?? 'admin'}-${index}`} style={{ padding: '4px 0' }}>
            <span
              style={{
                display: 'inline-flex',
                maxWidth: '100%',
                gap: '4px 12px',
                flexWrap: 'wrap',
                alignItems: 'baseline',
                overflowWrap: 'anywhere',
              }}
            >
              {contact.nickname ? <strong>{contact.nickname}</strong> : null}
              {contact.email ? <Text type="secondary">{contact.email}</Text> : null}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
