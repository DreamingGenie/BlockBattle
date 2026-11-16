import { useState, type ReactNode } from 'react';
import { navigate } from '../app/router';
import { useSession } from '../app/session';

export type ResultTone = 'win' | 'lose' | 'draw' | 'over';

const COLORS = ['#5fc6dd', '#f0b429', '#a35fd3', '#6fc36a', '#e0605a', '#5d7fe4', '#ea8d3c'];

/**
 * 경기 결과 팝업: 화면 가운데에 배경을 어둡게 깔고 결과 제목을 크게 띄운다.
 * 승리는 블록 조각이 터지듯 흩어지고, 패배는 카드가 흔들린다. 내용(점수·버튼)은 children으로 받는다.
 */
export function ResultModal({ tone, title, children }: { tone: ResultTone; title: ReactNode; children: ReactNode }) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="경기 결과">
      {tone === 'win' && (
        <div className="burst" aria-hidden="true">
          {Array.from({ length: 28 }, (_, i) => (
            <span
              key={i}
              style={{
                background: COLORS[i % COLORS.length],
                ['--dx' as string]: `${Math.round(Math.cos((i / 28) * Math.PI * 2) * (180 + (i % 4) * 60))}px`,
                ['--dy' as string]: `${Math.round(Math.sin((i / 28) * Math.PI * 2) * (140 + (i % 3) * 50))}px`,
                animationDelay: `${(i % 5) * 40}ms`,
              }}
            />
          ))}
        </div>
      )}
      <section className={`card result result-${tone} stack`}>
        <h2 className="result-title">{title}</h2>
        {children}
      </section>
    </div>
  );
}

export const APPEAL_EMAIL = 'dreaminggenie@naver.com';

/**
 * 봇 의심 알림(T-39): 서버가 자동 플레이로 판정해 결과를 전적·랭킹에 넣지 않았을 때 결과 팝업 대신 띄운다.
 * 사람이라면 스크린샷·닉네임·유저 코드(태그)·경기 번호를 메일로 보내 소명하게 안내한다.
 */
export function BotNotice({ matchId, onRetry }: { matchId: number; onRetry: () => void }) {
  const { me } = useSession();
  const [copied, setCopied] = useState<string | null>(null);
  const who = me ? `${me.nickname}#${me.tag}` : '-';
  const info = `닉네임: ${me?.nickname ?? '-'}\n유저 코드: ${me?.tag ?? '-'}\n경기 번호: ${matchId}`;
  const mailto = `mailto:${APPEAL_EMAIL}?subject=${encodeURIComponent(`[블록 배틀 소명] ${who} 경기 ${matchId}`)}&body=${encodeURIComponent(`${info}\n\n(스크린샷을 첨부해 주세요)\n`)}`;
  const copy = async (label: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(label);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      setCopied(null);
    }
  };
  return (
    <div className="modal-backdrop" role="alertdialog" aria-modal="true" aria-labelledby="bot-title">
      <section className="card result result-bot stack">
        <div className="bot-badge" aria-hidden="true">!</div>
        <h2 id="bot-title" className="result-title">BOT DETECTED</h2>
        <p className="result-sub">자동 플레이(봇)로 탐지되어 이번 결과는 기록되지 않습니다.</p>
        <p className="muted small bot-why">사람이 할 수 없는 속도로 블록이 놓였거나, 키 입력 없이 블록이 움직였습니다.</p>
        <div className="bot-appeal">
          <strong>봇이 아니라면 소명해 주세요</strong>
          <ol>
            <li>이 화면의 <b>스크린샷</b>을 찍습니다.</li>
            <li>
              <b>닉네임</b>과 <b>유저 코드</b>를 적어{' '}
              <button className="link inline" onClick={() => copy('mail', APPEAL_EMAIL)}>
                {APPEAL_EMAIL}
              </button>
              으로 보내 주세요.
            </li>
          </ol>
          <dl className="bot-info">
            <dt>닉네임</dt>
            <dd>{me?.nickname ?? '-'}</dd>
            <dt>유저 코드</dt>
            <dd className="num">#{me?.tag ?? '-'}</dd>
            <dt>경기 번호</dt>
            <dd className="num">{matchId}</dd>
          </dl>
        </div>
        <p className="keys-notice ok" role="status">
          {copied === 'mail' ? '메일 주소를 복사했습니다.' : copied === 'info' ? '소명 정보를 복사했습니다.' : ' '}
        </p>
        <div className="row">
          <a className="button-like" href={mailto}>
            메일 쓰기
          </a>
          <button className="secondary" onClick={() => copy('info', `${APPEAL_EMAIL}\n${info}`)}>
            정보 복사
          </button>
          <button className="secondary" onClick={onRetry}>
            다시 하기
          </button>
          <button className="secondary" onClick={() => navigate('/lobby')}>
            로비로
          </button>
        </div>
      </section>
    </div>
  );
}
