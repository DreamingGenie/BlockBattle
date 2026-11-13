import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';

/** 친구 대전 입구(FR-ROM-01·02): 방 만들기(POST /rooms) 또는 코드로 들어가기 */
export function RoomEntryScreen() {
  const { api } = useSession();
  const [roundCount, setRoundCount] = useState(1);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const created = await api.post<{ roomCode: string }>('/rooms', { roundCount });
      navigate(`/room/${created.roomCode}`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  function enter(event: FormEvent) {
    event.preventDefault();
    const value = code.trim().toUpperCase();
    if (!/^[A-Z0-9]{6}$/.test(value)) {
      setError('방 코드는 6자리 영문 대문자·숫자입니다.');
      return;
    }
    navigate(`/room/${value}`);
  }

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/lobby')}>
          ← 로비
        </button>
        <strong>친구 대전</strong>
        <span />
      </header>
      <section className="grid menu">
        <div className="card stack">
          <h2>방 만들기</h2>
          <label className="row">
            <input type="radio" checked={roundCount === 1} onChange={() => setRoundCount(1)} /> 1판
          </label>
          <label className="row">
            <input type="radio" checked={roundCount === 3} onChange={() => setRoundCount(3)} /> 3판 2선승
          </label>
          <button onClick={create} disabled={busy}>
            방 만들기
          </button>
        </div>
        <form className="card stack" onSubmit={enter}>
          <h2>코드로 들어가기</h2>
          <input value={code} onChange={(e) => setCode(e.target.value)} maxLength={6} placeholder="K7Q2MX" aria-label="방 코드" />
          <button type="submit">들어가기</button>
        </form>
      </section>
      {error && <p className="error">{error}</p>}
    </main>
  );
}
