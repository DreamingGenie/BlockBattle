import { useState } from 'react';

/** REQ-021: 모바일 브라우저 접속 안내. 초대 링크로 들어왔으면 주소를 복사해 데스크톱에서 열 수 있게 한다 */
export function MobileNoticeScreen() {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <main className="center">
      <section className="notice">
        <h1 className="wordmark">블록 배틀</h1>
        <p>키보드로 하는 게임이라 휴대폰·태블릿에서는 할 수 없습니다.</p>
        <p className="muted">PC의 Chrome·Edge·Firefox·Safari에서 이 주소를 열어 주세요.</p>
        <code className="address">{window.location.href}</code>
        <button onClick={copy}>{copied ? '복사했습니다' : '주소 복사'}</button>
      </section>
    </main>
  );
}
