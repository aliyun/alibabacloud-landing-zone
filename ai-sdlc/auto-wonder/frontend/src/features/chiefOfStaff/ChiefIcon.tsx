interface ChiefIconProps { size?: number; className?: string; }

export function ChiefIcon({ size = 24, className }: ChiefIconProps) {
  return (
    <svg className={className ? `aw-chief-icon ${className}` : 'aw-chief-icon'} width={size} height={size} viewBox="0 0 24 24"
      aria-hidden="true" focusable="false" shapeRendering="geometricPrecision">
      <path d="M12 3v3m-6.5 0h13A3.5 3.5 0 0 1 22 9.5v6a3.5 3.5 0 0 1-3.5 3.5H11l-4 3v-3H5.5A3.5 3.5 0 0 1 2 15.5v-6A3.5 3.5 0 0 1 5.5 6Z"
        stroke="var(--aw-accent-text)" fill="rgba(var(--aw-accent-rgb), .12)" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={12} cy={2.7} r={1.6} fill="var(--aw-accent-text)" />
      <rect x={6} y={10} width={12} height={5.7} rx={2.85} fill="var(--aw-accent-text)" />
      <path d="M9.4 12.8h.1m5 0h.1" stroke="var(--aw-panel)" strokeWidth={1.7} strokeLinecap="round" />
    </svg>
  );
}
