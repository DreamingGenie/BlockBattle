/** 콜백 ref로 쓴다: 결과 카드가 나타나면 보드 아래에 가려지지 않게 화면 안으로 스크롤한다 */
export function revealOnMount(el: HTMLElement | null): void {
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}
