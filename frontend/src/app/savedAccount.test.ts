import { describe, expect, it } from 'vitest';
import { clearSavedAccount, loadSavedAccount, saveSavedAccount } from './savedAccount';

function memoryStore() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
}

/**
 * 깨지면 로그인 화면에 엉뚱한 '이어 하기' 버튼이 뜨거나, 저장소를 못 쓰는 브라우저에서
 * '이어 하기'를 골랐는데 버튼 없이 쿠키만 남아 계정이 조용히 사라진다(부르는 쪽은 false면 완전 로그아웃으로 바꾼다)
 */
describe('savedAccount', () => {
  it('저장한 이름표를 그대로 읽고, 지우면 없다', () => {
    const s = memoryStore();
    expect(saveSavedAccount({ nickname: '지니', tag: '9269', userType: 'GUEST' }, s)).toBe(true);
    expect(loadSavedAccount(s)).toEqual({ nickname: '지니', tag: '9269', userType: 'GUEST' });
    clearSavedAccount(s);
    expect(loadSavedAccount(s)).toBeNull();
  });

  it('깨진 값은 없는 것으로 본다', () => {
    const s = memoryStore();
    for (const raw of ['{', '{"nickname":"지니"}', '{"nickname":"","tag":"1"}', 'null']) {
      s.m.set('bb.savedAccount', raw);
      expect(loadSavedAccount(s)).toBeNull();
    }
  });

  it('저장소를 못 쓰면 저장 실패를 알린다', () => {
    const broken = { getItem: () => null, setItem: () => { throw new Error('QuotaExceeded'); }, removeItem: () => {} };
    expect(saveSavedAccount({ nickname: '지니', tag: '9269', userType: 'GUEST' }, broken)).toBe(false);
    expect(saveSavedAccount({ nickname: '지니', tag: '9269', userType: 'GUEST' }, null)).toBe(false);
  });
});
