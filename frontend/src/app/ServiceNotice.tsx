/**
 * 운영 공지 띠(서버 종료·점검 안내). 빌드할 때 VITE_SERVICE_NOTICE에 문구를 넣으면 모든 화면 맨 위에 계속 보인다.
 * 값이 없으면 아무것도 그리지 않아 평소 빌드에는 영향이 없다.
 * 예) VITE_SERVICE_NOTICE="오늘 15:30에 서버가 종료됩니다" npm run build
 */
const notice = (import.meta.env.VITE_SERVICE_NOTICE ?? '').trim();

export const hasServiceNotice = notice !== '';

export function ServiceNotice() {
  if (!hasServiceNotice) return null;
  return (
    <div className="service-notice" role="status">
      <span>{notice}</span>
    </div>
  );
}
