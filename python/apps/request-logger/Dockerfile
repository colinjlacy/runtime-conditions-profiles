FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY runtimeconditions ./runtimeconditions
COPY apps/request-logger ./apps/request-logger

ENV PYTHONPATH=/app
ENTRYPOINT ["python", "apps/request-logger/main.py"]
