FROM python:3.12-slim

WORKDIR /app
COPY extensions ./extensions
COPY demos/apps/request-logger-http-python/pyproject.toml ./pyproject.toml
COPY demos/apps/request-logger-http-python/src ./src

ENV PYTHONPATH=/app/src:/app/extensions/common-integrations/python:/app/extensions/env-configuration/python
ENTRYPOINT ["python", "-m", "request_logger_http.app"]
