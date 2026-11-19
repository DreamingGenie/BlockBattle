import { ApiError, type ApiClient } from '../api/client';

/**
 * 방 만들기·입장이 ALREADY_IN_ROOM이면 지금 묶인 방 코드(GET /users/me의 currentRoomCode). 아니면 null.
 * 서버는 대기·끝난 방이면 알아서 빼 주므로, 이 오류는 경기 중인 방에 남아 있을 때다 — 화면은 그 방으로 돌아가게 한다.
 */
export async function currentRoomOnConflict(api: ApiClient, error: unknown): Promise<string | null> {
  if (!(error instanceof ApiError) || error.code !== 'ALREADY_IN_ROOM') return null;
  try {
    return (await api.get<{ currentRoomCode?: string | null }>('/users/me')).currentRoomCode ?? null;
  } catch {
    return null;
  }
}
