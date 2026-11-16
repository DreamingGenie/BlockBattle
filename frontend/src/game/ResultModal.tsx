import type { ReactNode } from 'react';

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
