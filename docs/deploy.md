# 배포 (AWS EC2 1대)

기획서의 배포 구성대로 EC2 한 대에 Docker Compose로 MySQL·Redis·백엔드·AI·nginx를 올린다. 외부에는 nginx의 80·443만 열고, nginx가 프론트 정적 파일과 `/api`·`/ws`(WebSocket)·`/ai`를 같은 출처로 받는다. Refresh 쿠키가 `Secure`라 HTTPS가 필요해서, 도메인 없이 `{공인IP를 -로 이은 것}.sslip.io` 주소에 Let's Encrypt 인증서를 붙인다.

| 파일 | 내용 |
| --- | --- |
| `deploy/docker-compose.yml` | 운영 서비스 5개. 비밀값은 서버의 `deploy/.env`에서 읽는다 |
| `deploy/nginx.conf.template` | HTTPS·프록시·초대 링크(`/room/{code}`) 대체 |
| `deploy/server-setup.sh` | 서버 처음 한 번: Docker·certbot 설치, swap 2GB, `.env` 무작위 비밀값 생성, 인증서 발급·갱신 훅 |
| `deploy/deploy.sh` | 로컬에서 jar·프론트를 빌드해 서버로 올리고 기동 |
| `backend/Dockerfile`, `ai/Dockerfile` | 운영 이미지 |

**1. EC2 만들기** — Ubuntu 24.04, t3.medium(2 vCPU·4GB, NFR-02 기준 사양), 디스크 20GB. 보안 그룹은 22(내 IP)·80·443만 연다. 탄력적 IP를 붙여 두면 껐다 켜도 주소가 같다.

**2. 배포** — Git Bash에서 실행한다. 처음이면 서버 준비까지 한 번에 한다.

```bash
bash deploy/deploy.sh <공인 IP> <키 파일.pem>
```

끝나면 `https://<공인 IP의 .을 -로>.sslip.io`로 접속한다. 다시 배포할 때도 같은 명령을 쓴다(비밀값·인증서·DB 데이터는 그대로 남는다).

운영 비밀값(`JWT_SECRET`, DB 비밀번호)은 서버의 `deploy/.env`(권한 600)에만 있고 저장소에는 `deploy/.env.example`만 있다.

**공지 띠** — 서버 종료·점검을 알릴 때는 빌드할 때 `VITE_SERVICE_NOTICE`에 문구를 넣는다. 모든 화면 맨 위에 검은 띠로 빨간 글자가 오른쪽에서 왼쪽으로 흐른다. 값이 없으면(평소 `deploy.sh`) 띠가 나오지 않는다. 백엔드를 다시 띄우지 않고 프론트만 바꾸려면 빌드한 `frontend/dist`의 내용을 서버의 `~/block-battle/frontend/dist/`에 덮어쓴다(nginx가 이 폴더를 바로 읽는다). 이미 열려 있는 탭은 새로고침해야 보인다.

```bash
cd frontend && VITE_SERVICE_NOTICE="오늘 15:30에 서버 운영을 종료합니다" npm run build
```

**요청 한도·상한** — 남용과 메모리 고갈에 대비해 아래 값을 둔다(보안 점검 H-3). IP 기준이라 같은 와이파이 뒤의 여러 사람이 함께 쓰므로, 시연처럼 한곳에서 많이 접속하면 `nginx.conf.template`의 값을 늘린다. 한도를 넘으면 nginx가 429로 답한다.

| 대상 | 값 |
| --- | --- |
| `POST /api/v1/auth/guest` | IP당 분당 30회, 순간 50회 |
| 방·경기 생성(`POST /api/v1/rooms`·`/matches`) | IP당 분당 60회, 순간 20회 |
| `/ai/` | IP당 초당 20회, 순간 40회(넘으면 화면이 규칙 AI로 대신 둔다) |
| `/ws` | IP당 동시 연결 60개 |
| 컨테이너 메모리 | mysql 768m · redis 384m · backend 1200m · ai 384m(CPU 1개) · nginx 128m, 로그는 서비스마다 10MB × 3개 |
| Redis | `maxmemory 256mb`, `volatile-lru`(오래 안 쓴 TTL 키부터 지움) |

Redis가 상한에 닿았는지는 서버에서 `docker compose -p block-battle exec redis redis-cli INFO stats | grep evicted_keys`로 본다. 0보다 크면 비정상적으로 키가 늘었다는 뜻이니 원인을 확인한다.
