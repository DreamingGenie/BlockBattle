/**
 * 앱이 놓인 하위 경로. 빌드할 때 VITE_BASE_PATH(예: /tetris/)로 정하고, 없으면 도메인 루트(/)다(vite.config.ts의 base).
 * /api·/ws·/ai·초대 링크(/room/{code})는 모두 이 경로 아래에 둔다. 끝은 늘 '/'로 끝난다.
 */
export const BASE_PATH: string = import.meta.env.BASE_URL.endsWith('/') ? import.meta.env.BASE_URL : `${import.meta.env.BASE_URL}/`;

/** 'api/v1' → '/tetris/api/v1'(하위 경로가 없으면 '/api/v1') */
export function appPath(path: string): string {
  return BASE_PATH + path.replace(/^\/+/, '');
}
