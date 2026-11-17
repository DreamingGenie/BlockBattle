/**
 * 화면 공용 선 아이콘(24×24, currentColor). 글자 옆 작은 표시용이라 장식이고 스크린 리더는 건너뛴다.
 * 아이콘 묶음 라이브러리를 들이지 않으려고 쓰는 모양만 직접 그렸다.
 */
const PATHS = {
  trophy: 'M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0zM7 6H4a3 3 0 0 0 3 4M17 6h3a3 3 0 0 1-3 4',
  clock: 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z',
  calendar: 'M4 6h16v14H4zM4 10h16M9 3v4M15 3v4',
  sort: 'M7 4v16M4 17l3 3 3-3M14 6h7M14 11h5M14 16h3',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
  users: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M16 3.5a4 4 0 0 1 0 7M18 14a7 7 0 0 1 4 7',
  bot: 'M5 9h14v10H5zM12 5v4M9 13v1M15 13v1M9 17h6M3 13v2M21 13v2M12 3.5v1.5',
  blocks: 'M3 9h6v6H3zM9 9h6v6H9zM9 3h6v6H9zM15 3h6v6h-6z',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 8v4l3 2',
  keyboard: 'M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10',
  logout: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11',
  arrow: 'M5 12h14M13 6l6 6-6 6',
  hash: 'M5 9h14M5 15h14M10 4 8 20M16 4l-2 16',
  dot: 'M12 12h.01',
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 16, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg
      className={`icon${className ? ` ${className}` : ''}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
