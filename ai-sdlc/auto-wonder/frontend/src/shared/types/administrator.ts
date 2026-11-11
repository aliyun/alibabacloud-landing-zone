/** 后端受控路径下发的可用平台管理员联系方式；缺字段时保持 null。 */
export interface AdministratorContact {
  nickname: string | null;
  email: string | null;
}

/** 从业务失败的 Result.data 中安全提取联系人列表。 */
export function readAdministratorContacts(data: unknown): AdministratorContact[] {
  if (!data || typeof data !== 'object') {
    return [];
  }
  const contacts = (data as { administratorContacts?: unknown }).administratorContacts;
  if (!Array.isArray(contacts)) {
    return [];
  }
  return contacts
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    .map((item) => ({
      nickname: typeof item.nickname === 'string' && item.nickname.trim() ? item.nickname : null,
      email: typeof item.email === 'string' && item.email.trim() ? item.email : null,
    }))
    .filter((item) => item.nickname !== null || item.email !== null);
}
