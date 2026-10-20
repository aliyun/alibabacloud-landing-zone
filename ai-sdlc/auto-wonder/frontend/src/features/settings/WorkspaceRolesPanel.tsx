import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Form, Input, Modal, Popconfirm, Space, Tag } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { formatNumber } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { WorkspaceRoleRequest, WorkspaceRoleVO } from './api';
import { useDeleteWorkspaceRole, useSaveWorkspaceRole, useWorkspaceRoles } from './hooks';
import { ReadOnlyFeatures } from './ReadOnlyFeatures';
import { workspaceRoleLabel } from './roles';

export function WorkspaceRolesPanel({ isAdmin }: { isAdmin: boolean }) {
  const { t } = useTranslation('settings');
  const { data: roles = [], isLoading } = useWorkspaceRoles();
  const save = useSaveWorkspaceRole();
  const remove = useDeleteWorkspaceRole();
  const accessCommand = useAccessCommand();
  const [editing, setEditing] = useState<WorkspaceRoleVO | null | undefined>(undefined);
  const [form] = Form.useForm<WorkspaceRoleRequest>();
  const open = editing !== undefined;
  const canEdit = (role: WorkspaceRoleVO | null | undefined) => isAdmin && (!role?.builtIn || role.code === 'READ_WRITE');
  const viewOnly = !canEdit(editing);
  useLocalizedValidation(form, open);

  const openEditor = (role: WorkspaceRoleVO | null) => {
    form.setFieldsValue({ name: role?.builtIn ? undefined : role ? workspaceRoleLabel(role) : '', readOnlyDomains: role?.readOnlyDomains ?? [] });
    setEditing(role);
  };
  const handleSave = async () => {
    let request: WorkspaceRoleRequest;
    try { request = await form.validateFields(); } catch { return; }
    await accessCommand('ADMIN', t('saveRole'), async () => {
      try {
        await save.mutateAsync({ id: editing?.id, request: { ...request, name: editing?.builtIn ? undefined : request.name?.trim(), version: editing?.version } });
        setEditing(undefined);
      } catch { /* The mutation displays the error and preserves the draft. */ }
    });
  };

  return <>
    <Space style={{ marginBottom: 16 }}>
      <Button type="primary" disabled={!isAdmin} onClick={() => accessCommand('ADMIN', t('createRole'), () => openEditor(null))}>{t('createRole')}</Button>
      <span style={{ color: 'var(--aw-muted)' }}>{t('rolesHint')}</span>
    </Space>
    <Table<WorkspaceRoleVO>
      rowKey="id" dataSource={roles} loading={isLoading} pagination={false}
      columns={[
        { title: t('role'), key: 'name', render: (_, role) => <Space>{workspaceRoleLabel(role)}{role.builtIn && <Tag>{t('builtIn')}</Tag>}</Space> },
        { title: t('readOnlyDomains'), key: 'permissions', render: (_, role) => role.administrator ? t('allPermissions') : t('readOnlyFeatures.selectedCount', { count: role.readOnlyDomains.filter((domain) => !['AUTH', 'USER', 'MCP'].includes(domain)).length }) },
        { title: t('assignedMembers'), dataIndex: 'memberCount', render: (count: number) => formatNumber(count) },
        { title: t('actions'), key: 'actions', render: (_, role) => <Space>
          <Button type="link" onClick={() => openEditor(role)}>{canEdit(role) ? t('edit') : t('viewRole')}</Button>
          {!role.builtIn && <Popconfirm title={t('deleteRoleConfirm', { name: workspaceRoleLabel(role) })} onConfirm={() => accessCommand('ADMIN', t('deleteRole'), () => remove.mutateAsync({ id: role.id, version: role.version }))}>
            <Button type="link" danger disabled={!isAdmin || role.memberCount > 0} loading={remove.isPending}>{t('deleteRole')}</Button>
          </Popconfirm>}
        </Space> },
      ]}
    />
    <Modal open={open} title={editing === null ? t('createRole') : viewOnly ? t('viewRole') : t('editRole')}
      width={820} styles={{ body: { maxHeight: '70vh', overflowY: 'auto', paddingRight: 8 } }}
      closable={{ 'aria-label': t('close') }} onCancel={() => setEditing(undefined)}
      onOk={handleSave} okText={t('save')} cancelText={t('cancel')} confirmLoading={save.isPending}
      footer={viewOnly ? null : undefined} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false} disabled={viewOnly}>
        {editing?.builtIn ? <Form.Item label={t('roleName')}>
          <Input value={workspaceRoleLabel(editing)} disabled aria-label={t('roleName')} />
        </Form.Item> : <Form.Item name="name" label={t('roleName')} rules={[{ required: true, whitespace: true, message: t('roleNameRequired') }, { max: 80, message: t('roleNameLength') }]}>
          <Input maxLength={80} aria-label={t('roleName')} />
        </Form.Item>}
        {editing?.administrator ? <p>{t('allPermissions')}</p> : <>
          <p className="member-domain-hint">{t('rolePermissionHint')}</p>
          <Form.Item name="readOnlyDomains"><ReadOnlyFeatures disabled={viewOnly} /></Form.Item>
        </>}
      </Form>
    </Modal>
  </>;
}
