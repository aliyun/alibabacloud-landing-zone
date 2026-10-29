import { useEffect, useRef, useState } from 'react';

interface Props {
  value: string | number;
}

const FLASH_MS = 600;

export default function FlashValue({ value }: Props) {
  const previous = useRef(value);
  const [flashing, setFlashing] = useState(false);

  useEffect(() => {
    if (previous.current === value) return;
    previous.current = value;
    setFlashing(true);
    const timer = setTimeout(() => setFlashing(false), FLASH_MS);
    return () => clearTimeout(timer);
  }, [value]);

  return <span className={`insight-flash${flashing ? ' is-flash' : ''}`}>{value}</span>;
}
