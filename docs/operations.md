# 서버 이전·종료와 임시 운영

서버를 내리거나 옮길 때, 그리고 다른 서비스가 쓰는 서버의 한 경로 아래에 임시로 올릴 때의 절차다. 처음 배포하는 절차는 [deploy.md](deploy.md).

**서버 이전·종료(백업과 복원)** — 서버를 내리거나 다른 서버로 옮길 때 쓴다.

1. 백업: `bash deploy/backup.sh <공인 IP> <키 파일.pem> <받을 폴더>`. nginx·backend·ai를 먼저 멈춰 받는 동안 새 기록이 생기지 않게 하고, MySQL 덤프·테이블별 행 수·Redis 스냅숏·`.env`를 받아 체크섬을 확인한다. **받은 폴더에는 회원 데이터와 운영 비밀값이 있으니 저장소 밖에 두고 커밋하지 않는다.**
2. 내리기: 서버에서 `cd ~/block-battle/deploy && sudo docker compose down`. 다시 열려면 `sudo docker compose start`(백업 직후)나 `up -d`.
3. 복원: 새 서버를 [deploy.md](deploy.md)의 1번처럼 만든 뒤 `bash deploy/restore.sh <새 공인 IP> <키 파일.pem> <백업 폴더>`. 백업의 `.env`를 새 주소로 바꿔 올리고 → `deploy.sh`로 기동 → DB를 덤프로 덮어쓴 뒤 → 행 수를 백업 때와 대조한다. 빈 서버(`deploy/.env` 없음)에서만 돈다.

주소(sslip.io)가 바뀌면 브라우저 쿠키와 '이 기기에서 이어 하기'가 따라오지 않아 사용자는 다시 로그인한다. 회원·전적·랭킹은 DB에 있어 그대로다. Redis(방·Refresh 토큰)는 TTL이 있는 값이라 되살리지 않는다.

**하위 경로에 함께 올리기(BEEZ 서버 `/tetris`, 2026-11-19 ~ 11-21 임시, 11-21에 내리고 서버에서 데이터까지 지움)** — 다른 서비스가 쓰는 서버의 한 경로 아래에 올릴 때 쓴다. 프론트는 `VITE_BASE_PATH=/tetris/`로 빌드하면 `/api`·`/ws`·`/ai`·초대 링크가 모두 그 아래로 간다. 블록 배틀 컨테이너는 별도 compose 프로젝트로 `127.0.0.1:18080`(backend)·`18000`(ai)에만 열고, 호스트 nginx의 `dev.beez.ai.kr` server 블록에 include 한 줄(`deploy/beez-host/tetris-locations.conf`)과 요청 한도(`tetris-limits.conf`)만 더한다. Refresh 쿠키 경로는 nginx `proxy_cookie_path`로 `/tetris/api/`로 바꾼다.

```bash
bash deploy/beez-host.sh up ../_local-data/block-battle-backup-20261119
```

```bash
bash deploy/beez-host.sh down
```

`up`은 처음 한 번만 백업 DB를 되살리고(서버에 `deploy/.env`가 없을 때), 다시 실행하면 코드만 바꿔 올린다. `down`은 nginx 설정을 원래대로 되돌리고 컨테이너를 내리며 데이터 볼륨은 남긴다(`purge`는 볼륨까지 지움). 서버 접속은 `~/.ssh/config`의 `Host beez`를 쓴다(`BEEZ_SSH`로 바꿀 수 있음).
