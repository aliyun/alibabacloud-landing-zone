interface ChiefIconProps { size?: number; className?: string; }

export function ChiefIcon({ size = 24, className }: ChiefIconProps) {
  return (
    <svg className={className ? `aw-chief-icon ${className}` : 'aw-chief-icon'} width={size} height={size} viewBox="0 0 24 24"
      aria-hidden="true" focusable="false" shapeRendering="geometricPrecision">
      <path d="M5 2h14a4 4 0 0 1 4 4v7a4 4 0 0 1-4 4h-6l-4.2 3.4c-.65.53-1.6.06-1.6-.77V17H5a4 4 0 0 1-4-4V6a4 4 0 0 1 4-4Z" fill="var(--aw-accent)" />
      <circle cx="7.5" cy="9.5" r="1.5" fill="var(--aw-primary-ink)" />
      <circle cx="12" cy="9.5" r="1.5" fill="var(--aw-primary-ink)" />
      <circle cx="16.5" cy="9.5" r="1.5" fill="var(--aw-primary-ink)" />
    </svg>
  );
}
