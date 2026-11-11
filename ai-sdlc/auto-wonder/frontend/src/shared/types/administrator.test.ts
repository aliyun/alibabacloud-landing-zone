import { describe, expect, it } from 'vitest';
import { readAdministratorContacts } from './administrator';

describe('readAdministratorContacts', () => {
  it('extracts valid contacts from a structured failure payload', () => {
    expect(readAdministratorContacts({
      administratorContacts: [
        { nickname: '周宁', email: 'zhou@example.com' },
        { nickname: 'Alex', email: null },
      ],
    })).toEqual([
      { nickname: '周宁', email: 'zhou@example.com' },
      { nickname: 'Alex', email: null },
    ]);
  });

  it('returns an empty list for missing or malformed payloads', () => {
    expect(readAdministratorContacts(null)).toEqual([]);
    expect(readAdministratorContacts('text')).toEqual([]);
    expect(readAdministratorContacts({ administratorContacts: 'nope' })).toEqual([]);
    expect(readAdministratorContacts({ administratorContacts: [null, 1, 'x'] })).toEqual([]);
  });

  it('drops blank fields and entries without any usable value', () => {
    expect(readAdministratorContacts({
      administratorContacts: [
        { nickname: '   ', email: 'only@example.com' },
        { nickname: null, email: '' },
      ],
    })).toEqual([{ nickname: null, email: 'only@example.com' }]);
  });
});
