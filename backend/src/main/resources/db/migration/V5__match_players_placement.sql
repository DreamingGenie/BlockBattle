-- 최대 4인 친구 대전(2026-11-19): 경기 안 등수. 마지막까지 남은 사람이 1, topout·퇴장·끊김 순서의 역순으로 매긴다.
-- 동시에 탈락한 사람은 같은 등수다. 싱글·vs AI와 이 컬럼 전의 경기는 NULL이다.
ALTER TABLE match_players ADD COLUMN placement TINYINT NULL;
