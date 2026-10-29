import { EditOutlined } from '@ant-design/icons';
import { Button, Popover } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { allows } from '@/shared/auth/access';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useUpdateWorkitemTags, useWorkitemTagOptions } from '../hooks';
import { WorkitemTagList } from './WorkitemTagList';
import { WorkitemTagsEditor } from './WorkitemTagsEditor';
import type { Workitem } from '@/shared/types/workitem';

interface WorkitemTagsCellProps {
  workitem: Workitem;
  maxVisible?: number;
}

/** 列表/看板内的标签快捷编辑：展示 + 气泡编辑，点击不冒泡到卡片跳转。 */
export function WorkitemTagsCell({ workitem, maxVisible = 2 }: WorkitemTagsCellProps) {
  const { t } = useTranslation('workitem');
  const accessCommand = useAccessCommand();
  const accessLevel = useAuthStore((state) => state.accessLevel);
  const readOnlyDomains = useAuthStore((state) => state.readOnlyDomains);
  const canWrite = accessLevel !== null && allows(accessLevel, 'READ_WRITE')
    && (accessLevel === 'ADMIN' || !readOnlyDomains.includes('WORKITEM'));
  const { data: options } = useWorkitemTagOptions();
  const mutation = useUpdateWorkitemTags(workitem.id);
  const [open, setOpen] = useState(false);
  const tags = workitem.tags ?? [];

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      setOpen(false);
      return;
    }
    accessCommand('READ_WRITE', t('tags.editCommand'), () => setOpen(true), 'WORKITEM');
  };

  const handleSave = async (next: string[]) => {
    let saved = false;
    await accessCommand('READ_WRITE', t('tags.editCommand'), async () => {
      await mutation.mutateAsync({ tags: next });
      saved = true;
    }, 'WORKITEM');
    if (saved) setOpen(false);
  };

  return (
    <span
      data-testid={`workitem-tags-cell-${workitem.id}`}
      className="aw-workitem-tags-cell"
      style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, maxWidth: '100%' }}
      onClick={(event) => event.stopPropagation()}
    >
      <Popover
        trigger="click"
        open={open}
        onOpenChange={handleOpenChange}
        destroyOnHidden
        title={t('tags.edit')}
        content={(
          <WorkitemTagsEditor
            initialTags={tags}
            options={options ?? []}
            saving={mutation.isPending}
            onSave={handleSave}
            onCancel={() => setOpen(false)}
            autoFocus
          />
        )}
      >
        <span
          className="aw-workitem-tags-trigger"
          style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, maxWidth: '100%', cursor: 'pointer' }}
        >
          <WorkitemTagList tags={tags} maxVisible={maxVisible} showEmpty emptyText={t('tags.emptyPlaceholder')} />
          {canWrite ? (
            <Button
              className="aw-workitem-tags-edit"
              type="text"
              size="small"
              aria-label={t('tags.editAria')}
              title={t('tags.edit')}
              icon={<EditOutlined aria-hidden="true" />}
            />
          ) : null}
        </span>
      </Popover>
    </span>
  );
}
