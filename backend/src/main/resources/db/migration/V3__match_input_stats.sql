-- T-40: 봇 판정 근거값. 싱글·vs AI 결과와 함께 온 실제 키 입력 수와 입력 요약값(블록 간격·키 누름 시간 변동계수 등),
-- 서버가 계산한 초당 블록 수와 봇 쪽 신호 개수를 남긴다. 기준값을 실제 사용자 분포로 정하고 소명을 검토하는 데 쓴다.
ALTER TABLE matches
    ADD COLUMN key_presses INT NULL,
    ADD COLUMN input_stats JSON NULL;
