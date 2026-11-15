FROM python:3.12-alpine
ARG VERSION=dev
WORKDIR /app
COPY app.py backup.py index.html ./
ENV PYTHONUNBUFFERED=1 DB_PATH=/data/sweetspot.db PORT=8080 APP_VERSION=${VERSION}
VOLUME /data
EXPOSE 8080
CMD ["python", "app.py"]
