-- T-35: vs AI 난이도를 한 단계씩 쉽게 옮기고 맨 위에 IMPOSSIBLE을 둔다.
-- 탐색 강도는 그대로 이름만 한 칸씩 올라가므로(옛 EASY = 새 NORMAL, 옛 NORMAL = 새 HARD, 옛 HARD = 새 IMPOSSIBLE)
-- 기존 기록도 실제로 상대한 강도의 이름으로 옮긴다. 위에서부터 옮겨야 겹치지 않는다.
ALTER TABLE matches MODIFY ai_difficulty ENUM ('EASY', 'NORMAL', 'HARD', 'IMPOSSIBLE') NULL;
UPDATE matches SET ai_difficulty = 'IMPOSSIBLE' WHERE ai_difficulty = 'HARD';
UPDATE matches SET ai_difficulty = 'HARD' WHERE ai_difficulty = 'NORMAL';
UPDATE matches SET ai_difficulty = 'NORMAL' WHERE ai_difficulty = 'EASY';
