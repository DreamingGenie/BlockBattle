// 동시 100방 친구 대전 부하 시험(REQ-016·REQ-017·REQ-022, T-17).
// VU 1개 = 방 1개. 한 VU가 두 플레이어의 STOMP 연결을 모두 열어 lock 전송 → 상대 BOARD 수신 지연을 같은 시계로 잰다(NFR-01).
// 실행 방법은 loadtest/README.md.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { setInterval, clearInterval, setTimeout } from 'k6/timers';
import { WebSocket } from 'k6/experimental/websockets';
import { PieceSequence } from './engine.js';
import { choose, toGrid } from './bot.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';
const ROOMS = Number(__ENV.ROOMS || 100);
const RAMP_S = Number(__ENV.RAMP_S || 60);
const HOLD_S = Number(__ENV.HOLD_S || 600);
const LOCK_MS = 500;
const ROLE_MS = 5000;
const WINDOW_MS = 10000;

const lockToBoard = new Trend('lock_to_opponent_board', true);
// 진단: 같은 호스트 시계라 BOARD 봉투의 sentAt(서버 발송 시각)으로 지연을 둘로 나눈다
const lockToPublish = new Trend('diag_lock_to_server_publish', true);
const publishToReceive = new Trend('diag_server_publish_to_receive', true);
const invalidLock = new Counter('invalid_lock');
const wsFailure = new Rate('ws_failure');
const locksSent = new Counter('locks_sent');
const skippedTicks = new Counter('lock_ticks_skipped');
const topouts = new Counter('topouts');
const attackEvents = new Counter('attack_events');
const attackLines = new Counter('attack_lines');
const profileWindows = new Counter('attack_profile_windows');
const profileWindowsOk = new Counter('attack_profile_windows_ok');

export const options = {
  scenarios: {
    rooms: { executor: 'per-vu-iterations', vus: ROOMS, iterations: 1, maxDuration: `${RAMP_S + HOLD_S + 180}s` },
  },
  thresholds: {
    lock_to_opponent_board: ['p(95)<=200'],
    ws_failure: ['rate<=0.001'],
    invalid_lock: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

function guest(name) {
  const res = http.post(`${BASE}/api/v1/auth/guest`, JSON.stringify({ nickname: name }), {
    headers: { 'Content-Type': 'application/json' },
  });
  const data = res.json('data');
  return { userId: data.userId, token: data.accessToken };
}

function frame(command, headers, body) {
  let out = command + '\n';
  for (const k of Object.keys(headers)) out += `${k}:${headers[k]}\n`;
  return out + '\n' + (body || '') + '\u0000';
}

function parseFrames(data) {
  const frames = [];
  for (const raw of String(data).split('\u0000')) {
    const text = raw.replace(/^\n+/, '');
    if (!text) continue;
    const split = text.indexOf('\n\n');
    const head = split >= 0 ? text.slice(0, split) : text;
    const lines = head.split('\n');
    const headers = {};
    for (let i = 1; i < lines.length; i++) {
      const at = lines[i].indexOf(':');
      if (at > 0) headers[lines[i].slice(0, at)] = lines[i].slice(at + 1);
    }
    frames.push({ command: lines[0], headers, body: split >= 0 ? text.slice(split + 2) : '' });
  }
  return frames;
}

export default function () {
  const vu = exec.vu.idInTest; // 1..ROOMS
  const startTime = exec.scenario.startTime;
  const holdStart = startTime + RAMP_S * 1000;
  const endAt = holdStart + HOLD_S * 1000;
  sleep(((vu - 1) * RAMP_S) / ROOMS); // 1분 동안 고르게 방을 늘린다

  const a = guest(`봇${vu}가`);
  const b = guest(`봇${vu}나`);
  const code = http.post(`${BASE}/api/v1/rooms`, JSON.stringify({ roundCount: 1 }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${a.token}` },
  }).json('data.roomCode');
  http.post(`${BASE}/api/v1/rooms/${code}/join`, null, { headers: { Authorization: `Bearer ${b.token}` } });

  const players = [a, b].map((p, index) => ({
    ...p, index, ws: null, connected: false, closing: false, playing: false, awaiting: false,
    seq: 0, pieceIndex: 0, board: null, combo: -1, sequence: null, sentAt: {}, timer: null,
  }));
  const inHold = () => { const now = Date.now(); return now >= holdStart && now < endAt; };
  // 10초 구간마다 방향별(0→1, 1→0)로 2줄 이상 ATTACK이 있었는지
  const windows = {};

  function send(p, destination, body) {
    p.ws.send(frame('SEND', { destination, 'content-type': 'application/json' }, JSON.stringify(body)));
  }

  function startPlaying(p, payload) {
    p.playing = false;
    p.awaiting = false;
    p.seq = 0;
    p.pieceIndex = 0;
    p.combo = -1;
    p.sentAt = {};
    p.board = new Uint8Array(220);
    p.sequence = new PieceSequence(payload.seed);
    if (p.timer !== null) clearInterval(p.timer);
    p.timer = null;
    const offset = p.index * (LOCK_MS / 2); // 두 사람은 250ms 엇갈려 보낸다
    setTimeout(() => {
      if (p.closing) return;
      p.playing = true;
      p.timer = setInterval(() => tick(p), LOCK_MS);
    }, Math.max(0, payload.startsAt - Date.now()) + offset);
  }

  function tick(p) {
    if (!p.playing || p.closing) return;
    if (p.awaiting) { // 직전 lock의 BOARD를 아직 못 받았다
      if (inHold()) skippedTicks.add(1);
      return;
    }
    const piece = p.sequence.at(p.pieceIndex);
    const role = (Math.floor(Date.now() / ROLE_MS) + p.index) % 2 === 0 ? 'attack' : 'defend';
    const move = choose(p.board, piece, role, p.combo);
    if (move === null) {
      p.playing = false;
      if (inHold()) topouts.add(1);
      send(p, `/app/rooms/${code}/topout`, { seq: p.seq });
      return;
    }
    const seq = p.seq + 1;
    p.awaiting = true;
    p.sentAt[seq] = Date.now();
    send(p, `/app/rooms/${code}/lock`, {
      seq, piece, rotation: move.rotation, x: move.x, y: move.y, holdUsed: false, lastMove: 'DROP', linesCleared: move.lines,
    });
    if (inHold()) locksSent.add(1);
  }

  function onTopic(p, msg) {
    const payload = msg.payload;
    if (msg.type === 'COUNTDOWN') {
      startPlaying(p, payload);
    } else if (msg.type === 'BOARD') {
      if (payload.userId === p.userId) {
        p.board = toGrid(payload.board);
        p.seq = payload.seq;
        p.pieceIndex += 1;
        p.combo = payload.combo;
        p.awaiting = false;
      } else if (p.index === 0 || p.index === 1) {
        const other = players[1 - p.index];
        const sent = other.sentAt[payload.seq];
        if (sent !== undefined) {
          if (inHold()) {
            const now = Date.now();
            lockToBoard.add(now - sent);
            lockToPublish.add(msg.sentAt - sent);
            publishToReceive.add(now - msg.sentAt);
          }
          delete other.sentAt[payload.seq];
        }
      }
    } else if (msg.type === 'ATTACK' && p.index === 0) { // 방송은 두 연결에 모두 오므로 한쪽에서만 센다
      if (inHold()) {
        attackEvents.add(1);
        attackLines.add(payload.lines);
        if (payload.lines >= 2) {
          const w = Math.floor((Date.now() - holdStart) / WINDOW_MS);
          const dir = payload.fromUserId === a.userId ? 'ab' : 'ba';
          windows[w] = windows[w] || {};
          windows[w][dir] = true;
        }
      }
    } else if (msg.type === 'GAME_OVER') {
      p.playing = false;
      if (payload.seriesWinnerUserId !== null && !p.closing) {
        setTimeout(() => { if (!p.closing) send(p, `/app/rooms/${code}/rematch`, { accept: true }); }, 200);
      }
    }
  }

  function onError(p, body) {
    if (body.code === 'INVALID_LOCK') {
      invalidLock.add(1);
      p.board = toGrid(body.payload.board);
      p.seq = body.payload.nextSeq - 1;
      p.awaiting = false;
    }
  }

  function connect(p) {
    const ws = new WebSocket(WS_URL);
    p.ws = ws;
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => ws.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', Authorization: `Bearer ${p.token}` }));
    ws.onmessage = (event) => {
      for (const f of parseFrames(event.data)) {
        if (f.command === 'CONNECTED') {
          p.connected = true;
          ws.send(frame('SUBSCRIBE', { id: 'room', destination: `/topic/rooms/${code}` }));
          ws.send(frame('SUBSCRIBE', { id: 'errors', destination: '/user/queue/errors' }));
          setTimeout(() => { if (!p.closing) send(p, `/app/rooms/${code}/ready`, { ready: true }); }, 500);
        } else if (f.command === 'MESSAGE') {
          const body = JSON.parse(f.body);
          if (f.headers.subscription === 'errors') onError(p, body);
          else onTopic(p, body);
        } else if (f.command === 'ERROR') {
          wsFailure.add(true);
        }
      }
    };
    ws.onerror = () => wsFailure.add(true);
    ws.onclose = () => {
      if (!p.closing) wsFailure.add(true); // 끝내기 전에 끊기면 비정상 끊김
    };
  }

  players.forEach(connect);

  setTimeout(() => {
    for (const p of players) {
      p.closing = true;
      p.playing = false;
      if (p.timer !== null) clearInterval(p.timer);
      wsFailure.add(false);
      if (p.ws) p.ws.close();
    }
    // 유지 구간 안의 온전한 10초 구간만 센다
    for (let w = 0; w < Math.floor(HOLD_S * 1000 / WINDOW_MS); w++) {
      if (Date.now() < holdStart) break;
      profileWindows.add(1);
      if (windows[w] && windows[w].ab && windows[w].ba) profileWindowsOk.add(1);
    }
  }, Math.max(0, endAt - Date.now()));
}
