-- T-41: 테트리스가 처음인 사람도 이길 수 있는 VERY_EASY를 EASY 아래에 둔다.
ALTER TABLE matches MODIFY ai_difficulty ENUM ('VERY_EASY', 'EASY', 'NORMAL', 'HARD', 'IMPOSSIBLE') NULL;
