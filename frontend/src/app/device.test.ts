import { describe, expect, it } from 'vitest';
import { isMobileBrowser } from './device';

/** REQ-021. 깨지면: 휴대폰에서 조작할 수 없는 게임 화면이 열리거나, 데스크톱(터치 노트북 포함)이 안내 화면에 막힌다 */
describe('isMobileBrowser', () => {
  const desktop = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';

  it('휴대폰·태블릿 user agent는 모바일이다', () => {
    const phone = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';
    const iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
    expect(isMobileBrowser({ userAgent: phone, coarseTouchOnly: true, screenWidth: 412 })).toBe(true);
    expect(isMobileBrowser({ userAgent: iphone, coarseTouchOnly: true, screenWidth: 390 })).toBe(true);
  });

  it('데스크톱 user agent라도 마우스 없는 좁은 터치 화면(iPadOS)이면 모바일이다', () => {
    expect(isMobileBrowser({ userAgent: desktop, coarseTouchOnly: true, screenWidth: 820 })).toBe(true);
  });

  it('데스크톱과 터치 노트북은 모바일이 아니다', () => {
    expect(isMobileBrowser({ userAgent: desktop, coarseTouchOnly: false, screenWidth: 1920 })).toBe(false);
    expect(isMobileBrowser({ userAgent: desktop, coarseTouchOnly: true, screenWidth: 1366 })).toBe(false);
  });
});
