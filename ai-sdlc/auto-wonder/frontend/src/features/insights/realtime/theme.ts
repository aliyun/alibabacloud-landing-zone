import type { CSSProperties } from 'react';

export const BRAND = {
  orange: '#FF6A00',
  orangeLight: '#FF8B33',
  orangeBg: '#FFF3EA',
  orangeBorder: '#FFD4AE',
  cardBorder: '#ebedf0',
  textMuted: '#8c8c8c',
  green: '#52c41a',
  red: '#ff4d4f',
  gold: '#faad14',
  grey: '#d9d9d9',
};

export const cardStyle: CSSProperties = {
  background: '#fff',
  border: `1px solid ${BRAND.cardBorder}`,
  borderRadius: 10,
  padding: 14,
};
