export interface AiResultRendererProps<T> {
  value: T;
  onChange: (next: T) => void;
  disabled?: boolean;
}
