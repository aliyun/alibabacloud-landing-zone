import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Button, Cascader, Collapse, ConfigProvider, DatePicker, Drawer, Dropdown, Input, Modal, Popconfirm, Popover, Select, TimePicker, Tooltip, TreeSelect, message, notification, theme } from 'antd';
import { AppearanceProvider, useAppearance } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';

function Fixture() {
  const [selected, setSelected] = useState('');
  const [modal, setModal] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const { setMode } = useAppearance();
  const { token } = theme.useToken();
  return <main style={{ padding: 30 }}>
    <output data-testid="motion">{String(token.motion)}</output>
    <output data-testid="selected">{selected}</output>
    <Button onClick={() => setMode('light')}>Light</Button><Button onClick={() => setMode('dark')}>Dark</Button>
    <Dropdown trigger={['click']} menu={{ items: [{ key: 'action', label: 'Menu action' }], onClick: () => setSelected('menu') }}><Button>Menu</Button></Dropdown>
    <Select aria-label="Single" style={{ width: 160 }} options={[{ value: 'alpha', label: 'Alpha' }]} onChange={setSelected} />
    <Select aria-label="Multiple" mode="multiple" style={{ width: 160 }} options={[{ value: 'beta', label: 'Beta' }]} onChange={v => setSelected(v.join(','))} />
    <TreeSelect aria-label="Tree" style={{ width: 160 }} treeData={[{ value: 'tree', title: 'Tree option' }]} onChange={setSelected} />
    <Cascader aria-label="Cascade" options={[{ value: 'parent', label: 'Parent', children: [{ value: 'child', label: 'Child' }] }]} onChange={v => setSelected(v.join('/'))} />
    <DatePicker aria-label="Date" /><TimePicker aria-label="Time" />
    <Button onClick={() => setModal(true)}>Modal</Button><Modal title="Dialog" open={modal} onCancel={() => setModal(false)} onOk={() => setModal(false)}><Input aria-label="Draft" /></Modal>
    <Button onClick={() => setDrawer(true)}>Drawer</Button><Drawer title="Drawer panel" open={drawer} onClose={() => setDrawer(false)}>Drawer content</Drawer>
    <Tooltip title="Tooltip content"><Button>Tooltip</Button></Tooltip>
    <Popover content="Popover content" trigger="click"><Button>Popover</Button></Popover>
    <Popconfirm title="Confirm action" onConfirm={() => setSelected('confirmed')}><Button>Confirm</Button></Popconfirm>
    <Button onClick={() => Modal.confirm({ title: 'Static dialog', closable: true })}>Static modal</Button>
    <Button onClick={() => message.info('Static message')}>Message</Button>
    <Button onClick={() => notification.info({ message: 'Static notification' })}>Notification</Button>
    <ConfigProvider theme={{ token: { fontSize: 12 } }}><Collapse items={[{ key: 'nested', label: 'Expand', children: 'Expanded content' }]} /></ConfigProvider>
  </main>;
}
createRoot(document.getElementById('root')!).render(<LocaleProvider><AppearanceProvider accent="#f97316"><Fixture /></AppearanceProvider></LocaleProvider>);
