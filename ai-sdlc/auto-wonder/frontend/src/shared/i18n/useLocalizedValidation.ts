import { useEffect } from 'react';
import type { FormInstance } from 'antd';
import { useTranslation } from 'react-i18next';

/** Refresh displayed validation messages after a locale change, without validating pristine fields. */
export function useLocalizedValidation(form: FormInstance, enabled = true): void {
  const { t } = useTranslation('common');
  useEffect(() => {
    if (!enabled) return;
    const names = form.getFieldsError().filter(field => field.errors.length > 0).map(field => field.name);
    if (names.length) void form.validateFields(names).catch(() => {});
  }, [form, t, enabled]);
}
