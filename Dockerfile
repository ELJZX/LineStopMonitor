FROM python:3.12-slim

WORKDIR /app

COPY web/server.py /app/server.py

EXPOSE 7777

CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "7777", \
     "--db", "/data/linestop_web.db", "--apk", "/apk/app-debug.apk"]
