# syntax=docker/dockerfile:1
FROM python:3.11-slim-bookworm AS build
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends build-essential && rm -rf /var/lib/apt/lists/*
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
COPY services/ml-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir -r requirements.txt

FROM python:3.11-slim-bookworm AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 libgl1 libglib2.0-0 && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 ckd && useradd --uid 10001 --gid ckd --create-home ckd \
    && mkdir -p /home/ckd/.cache && chown -R 10001:10001 /home/ckd/.cache
COPY --from=build /opt/venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
WORKDIR /app
COPY --chown=10001:10001 services/ml-service/ /app/ckd_ml/
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 ML_MODELS_DIR=/models HF_HOME=/home/ckd/.cache/huggingface
USER 10001:10001
# Check native-library and processor imports without loading checkpoints or producing predictions.
RUN python -c "import fastapi, torch, torchvision, paddle, cv2; from transformers import TrOCRProcessor, VisionEncoderDecoderModel"
EXPOSE 8001
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 CMD ["python", "-m", "ckd_ml.healthcheck"]
CMD ["uvicorn", "ckd_ml.main:app", "--host", "0.0.0.0", "--port", "8001", "--no-access-log"]
