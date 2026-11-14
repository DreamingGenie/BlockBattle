/** 휴대폰·태블릿 브라우저인지(REQ-021). 키보드 전용 게임이라 모바일이면 데스크톱 안내 화면만 보인다 */
export function isMobileBrowser(env: { userAgent: string; coarseTouchOnly: boolean; screenWidth: number }): boolean {
  if (/Android|iPhone|iPad|iPod|Mobile/i.test(env.userAgent)) return true;
  // iPadOS 사파리는 데스크톱 user agent를 쓰므로 '마우스 없는 터치 화면 + 좁은 화면'도 모바일로 본다
  return env.coarseTouchOnly && env.screenWidth < 1024;
}

export function currentDevice() {
  return {
    userAgent: navigator.userAgent,
    coarseTouchOnly: window.matchMedia?.('(hover: none) and (pointer: coarse)').matches ?? false,
    screenWidth: window.screen?.width ?? window.innerWidth,
  };
}
