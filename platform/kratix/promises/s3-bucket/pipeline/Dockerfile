FROM python:3.12-alpine

RUN pip install --no-cache-dir PyYAML==6.0.2 boto3==1.35.82

COPY configure.py /usr/local/bin/configure.py

ENTRYPOINT ["python", "/usr/local/bin/configure.py"]
