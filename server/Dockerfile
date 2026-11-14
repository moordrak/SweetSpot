FROM python:3.12-alpine
WORKDIR /app
COPY app.py index.html ./
ENV PYTHONUNBUFFERED=1 DB_PATH=/data/sweetspot.db PORT=8080
VOLUME /data
EXPOSE 8080
CMD ["python", "app.py"]
