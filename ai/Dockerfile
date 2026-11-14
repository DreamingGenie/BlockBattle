# 운영 이미지(T-29). 빌드 컨텍스트는 저장소 루트(규칙 시험 데이터 shared/rules-fixtures를 함께 넣는다).
FROM python:3.12-slim
WORKDIR /srv
# 실행에 필요한 것만 설치한다(requirements.txt의 pytest·httpx는 시험용)
RUN pip install --no-cache-dir fastapi==0.115.6 uvicorn==0.32.1 PyJWT==2.10.1
COPY ai/app ./app
COPY shared/rules-fixtures ./rules-fixtures
ENV RULES_DIR=/srv/rules-fixtures
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
