FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TZ=Asia/Kolkata STORAGE_LOCAL_DIR=/data/files
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY fleet_worker ./fleet_worker
RUN useradd --system --uid 10001 fleet && mkdir -p /data/files && chown -R fleet /data
USER fleet
CMD ["python", "-m", "fleet_worker.main"]
