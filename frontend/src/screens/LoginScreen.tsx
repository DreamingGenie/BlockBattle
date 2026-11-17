import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import { Icon } from '../app/Icon';
import { useSession } from '../app/session';

const NICKNAME = /^[가-힣A-Za-z0-9]{2,10}$/;

/** 게스트 로그인(FR-USR-01, API-001). 이 기기에 남겨 둔 계정이 있으면 '이어 하기'를 먼저 보인다 */
export function LoginScreen() {
  const { login, saved, resume, forget } = useSession();
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const value = nickname.trim();
    if (!NICKNAME.test(value)) {
      setError('닉네임은 한글·영문·숫자 2~10자로 입력해 주세요.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await login(value);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function continueSaved() {
    if (!saved) return;
    const name = `${saved.nickname}#${saved.tag}`;
    setBusy(true);
    setError(null);
    try {
      if (!(await resume())) setNotice(`${name}의 로그인이 만료돼 이어 할 수 없습니다. 7일 넘게 접속하지 않았거나 다른 곳에서 정리됐습니다.`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function forgetSaved() {
    setBusy(true);
    await forget();
    setNotice('이 기기에서 계정을 지웠습니다.');
    setBusy(false);
  }

  return (
    <main className="center">
      <section className="card narrow login-card">
        <h1>블록 배틀</h1>
        {saved && (
          <div className="saved-account">
            <p className="meta">이 기기에서 쓰던 계정</p>
            <button className="saved-continue" onClick={continueSaved} disabled={busy}>
              <span className="avatar" aria-hidden="true">
                {saved.nickname.slice(0, 1)}
              </span>
              <span className="saved-name">
                <b>{saved.nickname}</b>
                <span className="num">#{saved.tag}</span>
              </span>
              <span className="saved-go">
                이어 하기 <Icon name="arrow" size={15} />
              </span>
            </button>
            <button className="link small" onClick={forgetSaved} disabled={busy}>
              이 기기에서 지우기
            </button>
          </div>
        )}
        {notice && <p className="meta">{notice}</p>}
        <p className="muted">
          {saved
            ? '새 닉네임으로 시작하면 위 계정은 이 기기에서 더는 이어 할 수 없습니다.'
            : '닉네임만 정하면 바로 시작합니다. 같은 닉네임이어도 4자리 태그로 구분됩니다.'}
        </p>
        <form onSubmit={submit} className="stack">
          <label htmlFor="nickname">{saved ? '새 게스트 닉네임' : '닉네임'}</label>
          <input
            id="nickname"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={10}
            autoFocus={!saved}
            placeholder="2~10자"
          />
          {error && <p className="error">{error}</p>}
          <button type="submit" className={saved ? 'secondary' : undefined} disabled={busy}>
            {busy ? '들어가는 중…' : saved ? '새 게스트로 시작' : '게스트로 시작'}
          </button>
        </form>
      </section>
    </main>
  );
}
