FROM python:3.12-alpine
ARG VERSION=dev
# Ugrađeni Google klijent za kopiju na Google Drive (puni ga izdanje iz tajni repozitorija)
ARG GOOGLE_CLIENT_ID=
ARG GOOGLE_CLIENT_SECRET=
WORKDIR /app
COPY app.py backup.py index.html ./
ENV PYTHONUNBUFFERED=1 DB_PATH=/data/sweetspot.db PORT=8080 APP_VERSION=${VERSION} \
    SWEETSPOT_GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID} SWEETSPOT_GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
VOLUME /data
EXPOSE 8080
CMD ["python", "app.py"]
