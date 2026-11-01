FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY server.py /app/server.py
COPY static/ /app/static/
COPY data/ /app/data/

EXPOSE 8080
CMD ["sh", "-c", "python /app/server.py --host 0.0.0.0 --port ${PORT:-8080} --no-browser"]
