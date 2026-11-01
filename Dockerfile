# TagoHub 웹 버전 (Google Cloud Run 등에 올리는 용). 배포: web/deploy.ps1 (설명은 web/DEPLOY.md)
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    RATE_LIMIT_PER_MIN=90

WORKDIR /app
COPY web/server.py /app/server.py
COPY web/static /app/static
# 정류장 DB·지하철역·열차 시간표는 안드로이드 앱과 같은 파일을 그대로 씀 (서버가 켜질 때 따로 받지 않게 이미지에 넣음)
COPY app/src/main/assets/bus_stops.db /app/data/bus_stops.db
COPY app/src/main/assets/subway_stations.tsv /app/data/subway_stations.tsv
COPY app/src/main/assets/train_timetable.tsv /app/data/train_timetable.tsv

# 키(TAGO_SERVICE_KEY 등)는 이미지에 넣지 않고 배포할 때 환경변수로 줌. PORT 는 Cloud Run 이 정해 줌
CMD ["python", "/app/server.py"]
