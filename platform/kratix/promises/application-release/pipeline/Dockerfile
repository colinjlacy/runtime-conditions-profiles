FROM python:3.12-alpine

RUN pip install --no-cache-dir PyYAML==6.0.2

COPY resolver.py /usr/local/bin/resolver.py

ENTRYPOINT ["python", "/usr/local/bin/resolver.py"]

