import { liveLabel } from './presence';
import { usePresence } from './PresenceProvider';

/** 상단 바의 '● N명 플레이 중 · 접속 M명' 칩(T-49) */
export function LiveCount() {
  const { counts, failed } = usePresence();
  const label = liveLabel(counts, failed);
  const title =
    label.tone === 'wait'
      ? '인원 수를 불러오는 중입니다'
      : '최근 1분 안에 게임 화면에 있던 사람 수입니다. 같은 사람의 여러 탭은 한 명으로 셉니다.';
  return (
    <span className={`live-count ${label.tone}`} role="status" aria-live="polite" title={title}>
      <span className="live-dot" aria-hidden="true" />
      {label.tone === 'on' ? (
        <span className="live-main">
          <b className="num">{label.playing!.toLocaleString('ko-KR')}</b>
          {label.text}
        </span>
      ) : (
        <span className="live-main">{label.text}</span>
      )}
      {label.sub && <span className="live-sub num">{label.sub}</span>}
    </span>
  );
}
