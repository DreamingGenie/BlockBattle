import { useEffect, useState } from 'react';

/** 해시 라우터(T-18): #/lobby, #/solo, #/ai, #/room/K7Q2MX, #/records, #/rankings, #/matches/402 */
export interface Route {
  name: 'lobby' | 'solo' | 'ai' | 'room' | 'records' | 'rankings' | 'keys' | 'match';
  param?: string;
}

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  switch (parts[0]) {
    case 'solo':
      return { name: 'solo' };
    case 'ai':
      return { name: 'ai' };
    case 'room':
      return { name: 'room', param: parts[1]?.toUpperCase() };
    case 'records':
      return { name: 'records' };
    case 'rankings':
      return { name: 'rankings' };
    case 'keys':
      return { name: 'keys' };
    case 'matches':
      return parts[1] ? { name: 'match', param: parts[1] } : { name: 'records' };
    default:
      return { name: 'lobby' };
  }
}

export function navigate(path: string): void {
  window.location.hash = path.startsWith('#') ? path : `#${path}`;
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
