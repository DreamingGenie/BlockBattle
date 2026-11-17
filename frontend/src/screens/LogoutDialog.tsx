import { useEffect, useState } from 'react';
import { Icon } from '../app/Icon';
import type { LogoutMode } from '../app/session';

/** 로그아웃 방법 고르기: 이 기기에서 이어 하기를 남길지, 공용 PC라 완전히 지울지 */
export function LogoutDialog({ name, guest, onPick, onClose }: { name: string; guest: boolean; onPick: (mode: LogoutMode) => void; onClose: () => void }) {
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const pick = (mode: LogoutMode) => {
    setBusy(true);
    onPick(mode);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="card logout-dialog" role="dialog" aria-modal="true" aria-labelledby="logout-title" onClick={(e) => e.stopPropagation()}>
        <h2 id="logout-title">로그아웃</h2>
        <p className="meta">어떻게 나갈지 골라 주세요.</p>
        <button className="logout-option" onClick={() => pick('keep')} disabled={busy} autoFocus>
          <span className="logout-option-icon">
            <Icon name="user" size={20} />
          </span>
          <span>
            <b>로그아웃 (이 기기에서 이어 하기)</b>
            <span className="meta">
              이 브라우저의 로그인 화면에서 <b>{name}</b>로 다시 들어올 수 있습니다. 내 PC라면 이쪽을 고르세요.
            </span>
          </span>
        </button>
        <button className="logout-option danger" onClick={() => pick('full')} disabled={busy}>
          <span className="logout-option-icon">
            <Icon name="logout" size={20} />
          </span>
          <span>
            <b>완전히 로그아웃 (공용 PC)</b>
            <span className="meta">
              이 기기에 아무것도 남기지 않습니다.
              {guest && ' 게스트 계정이라 이 계정과 기록으로 다시 들어올 방법이 없습니다.'}
            </span>
          </span>
        </button>
        <button className="secondary" onClick={onClose} disabled={busy}>
          취소
        </button>
      </section>
    </div>
  );
}
