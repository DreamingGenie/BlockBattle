import { useEffect, useState } from 'react';
import { navigate } from '../app/router';
import {
  BIND_ACTIONS,
  DEFAULT_BINDINGS,
  MAX_KEYS_PER_ACTION,
  assignKey,
  clearKey,
  cloneBindings,
  isReservedKey,
  keyLabel,
  loadBindings,
  saveBindings,
  type BindAction,
  type KeyBindings,
} from '../game/keybindings';

interface Listening {
  action: BindAction;
  slot: number;
}

/** 키 설정(T-38): 칸을 누르고 원하는 키를 누르면 바로 저장된다. 기능 키(F1~F12·Tab·Windows 키 등)는 지정할 수 없다 */
export function KeySettingsScreen() {
  const [bindings, setBindings] = useState<KeyBindings>(() => loadBindings());
  const [listening, setListening] = useState<Listening | null>(null);
  const [notice, setNotice] = useState<{ text: string; tone: 'ok' | 'warn' } | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  const update = (next: KeyBindings) => {
    setBindings(next);
    saveBindings(next);
  };

  useEffect(() => {
    if (!listening) return;
    const onKey = (e: KeyboardEvent) => {
      e.preventDefault();
      e.stopPropagation();
      if (e.repeat) return;
      if (isReservedKey(e.code)) {
        setNotice({ text: `${keyLabel(e.code) ? `${keyLabel(e.code)} 키` : '그 키'}는 브라우저·시스템 기능 키라 쓸 수 없습니다.`, tone: 'warn' });
        return;
      }
      const { bindings: next, movedFrom } = assignKey(bindings, listening.action, listening.slot, e.code);
      update(next);
      setFlash(`${listening.action}:${e.code}`);
      const label = BIND_ACTIONS.find((a) => a.action === movedFrom)?.label;
      setNotice(
        movedFrom
          ? { text: `${keyLabel(e.code)} 키를 '${label}'에서 옮겨 왔습니다.`, tone: 'warn' }
          : { text: `${keyLabel(e.code)} 키를 지정했습니다.`, tone: 'ok' },
      );
      setListening(null);
    };
    const cancel = (e: MouseEvent) => {
      if (!(e.target as HTMLElement).closest('.key-slot')) setListening(null);
    };
    window.addEventListener('keydown', onKey, true);
    window.addEventListener('mousedown', cancel);
    return () => {
      window.removeEventListener('keydown', onKey, true);
      window.removeEventListener('mousedown', cancel);
    };
  }, [listening, bindings]);

  useEffect(() => {
    if (!flash) return;
    const t = setTimeout(() => setFlash(null), 600);
    return () => clearTimeout(t);
  }, [flash]);

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/lobby')}>
          ← 로비
        </button>
        <strong>키 설정</strong>
        <span />
      </header>
      <section className="keys-panel">
        <h2 className="pick-title">KEY CONFIG</h2>
        <p className="muted small keys-help">
          칸을 누른 뒤 원하는 키를 누르세요. 동작마다 키를 두 개까지 둘 수 있고, 바꾸면 바로 저장됩니다.
        </p>
        <ul className="key-list">
          {BIND_ACTIONS.map(({ action, label }) => (
            <li key={action} className="key-row">
              <span className="key-action">{label}</span>
              <span className="key-slots">
                {Array.from({ length: MAX_KEYS_PER_ACTION }, (_, slot) => {
                  const code = bindings[action][slot];
                  const active = listening?.action === action && listening.slot === slot;
                  return (
                    <span key={slot} className="key-slot-wrap">
                      <button
                        className={`key-slot${code ? '' : ' empty'}${active ? ' listening' : ''}${flash === `${action}:${code}` ? ' flash' : ''}`}
                        onClick={() => {
                          setNotice(null);
                          setListening(active ? null : { action, slot });
                        }}
                        aria-label={`${label} ${slot + 1}번 키${code ? `: ${keyLabel(code)}` : ' 비어 있음'}`}
                      >
                        {active ? '키를 누르세요' : code ? keyLabel(code) : '+'}
                      </button>
                      {code && !active && (
                        <button
                          className="key-clear"
                          aria-label={`${label} ${keyLabel(code)} 지우기`}
                          onClick={() => update(clearKey(bindings, action, slot))}
                        >
                          ×
                        </button>
                      )}
                    </span>
                  );
                })}
              </span>
            </li>
          ))}
        </ul>
        <p className={`keys-notice ${notice?.tone ?? ''}`} role="status">
          {notice?.text ?? ' '}
        </p>
        <div className="row keys-actions">
          <button
            className="secondary"
            onClick={() => {
              update(cloneBindings(DEFAULT_BINDINGS));
              setNotice({ text: '기본 키로 되돌렸습니다.', tone: 'ok' });
              setListening(null);
            }}
          >
            기본값으로
          </button>
          <button onClick={() => navigate('/lobby')}>완료</button>
        </div>
      </section>
    </main>
  );
}
