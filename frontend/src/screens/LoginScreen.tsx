import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import { useSession } from '../app/session';

const NICKNAME = /^[가-힣A-Za-z0-9]{2,10}$/;

/** 게스트 로그인(FR-USR-01, API-001) */
export function LoginScreen() {
  const { login } = useSession();
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState<string | null>(null);
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

  return (
    <main className="center">
      <section className="card narrow">
        <h1>블록 배틀</h1>
        <p className="muted">닉네임만 정하면 바로 시작합니다. 같은 닉네임이어도 4자리 태그로 구분됩니다.</p>
        <form onSubmit={submit} className="stack">
          <label htmlFor="nickname">닉네임</label>
          <input
            id="nickname"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={10}
            autoFocus
            placeholder="2~10자"
          />
          {error && <p className="error">{error}</p>}
          <button type="submit" disabled={busy}>
            {busy ? '들어가는 중…' : '게스트로 시작'}
          </button>
        </form>
      </section>
    </main>
  );
}
