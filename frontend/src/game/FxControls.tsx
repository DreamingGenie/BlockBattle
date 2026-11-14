import { useEffect, useState } from 'react';
import { getSettings, subscribeSettings, updateSettings } from './settings';

/** 게임 화면 상단의 소리·흔들림 설정(REQ-023). 바꾼 값은 브라우저에 남는다.
 * 누른 뒤 포커스를 풀어, Space(하드 드롭)가 버튼을 다시 누르지 않게 한다 */
export function FxControls() {
  const [s, setS] = useState(getSettings);
  useEffect(() => subscribeSettings(setS), []);

  return (
    <span className="fx-controls">
      <button className="link" onClick={(e) => (updateSettings({ muted: !s.muted }), e.currentTarget.blur())} aria-pressed={!s.muted} title="효과음 켜기·끄기">
        {s.muted ? '소리 끔' : '소리 켬'}
      </button>
      <input
        type="range"
        min={0}
        max={1}
        step={0.05}
        value={s.volume}
        disabled={s.muted}
        onChange={(e) => updateSettings({ volume: Number(e.target.value) })}
        onPointerUp={(e) => e.currentTarget.blur()}
        aria-label="음량"
      />
      <button className="link" onClick={(e) => (updateSettings({ shake: !s.shake }), e.currentTarget.blur())} aria-pressed={s.shake} title="화면 흔들림 켜기·끄기">
        {s.shake ? '흔들림 켬' : '흔들림 끔'}
      </button>
    </span>
  );
}
