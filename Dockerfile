FROM node:24-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM golang:1.27-bookworm AS go-builder
ARG BUILD_VERSION=""
ARG BUILD_COMMIT=""
ARG BUILD_REF=""
ARG BUILD_TIME=""
WORKDIR /build/go
COPY go/go.mod go/go.sum ./
RUN CGO_ENABLED=0 go mod download
COPY go/ ./
RUN bt="${BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" && \
    CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X github.com/agonie0v0/melokeep/internal/app.buildVersion=${BUILD_VERSION} -X github.com/agonie0v0/melokeep/internal/app.buildCommit=${BUILD_COMMIT} -X github.com/agonie0v0/melokeep/internal/app.buildRef=${BUILD_REF} -X github.com/agonie0v0/melokeep/internal/app.buildTime=${bt}" -o /out/melokeep ./cmd/melokeep

FROM debian:bookworm-slim AS telegram-api
ARG TELEGRAM_BOT_API_REF=e3e9dd8e5b3d7ab8537cd5a10dc31d5ffa8f82d1
ARG TELEGRAM_BUILD_JOBS=4
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates cmake g++ make gperf libssl-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git init . && git remote add origin https://github.com/tdlib/telegram-bot-api.git \
    && git fetch --depth 1 origin "$TELEGRAM_BOT_API_REF" && git checkout --detach FETCH_HEAD \
    && git submodule update --init --recursive --depth 1 \
    && cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/telegram \
    && cmake --build build --target install --parallel "$TELEGRAM_BUILD_JOBS" \
    && strip /opt/telegram/bin/telegram-bot-api

FROM python:3.14-slim-bookworm
LABEL org.opencontainers.image.title="MeloKeep" \
      org.opencontainers.image.source="https://github.com/Agonie0v0/melokeep"
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates ffmpeg libchromaprint-tools curl libssl3 zlib1g libstdc++6 tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 melokeep && useradd --uid 10001 --gid melokeep --create-home melokeep \
    && mkdir -p /music /data /app/frontend /app/helpers /licenses && chown -R melokeep:melokeep /music /data /app
WORKDIR /app
ENV HOME=/home/melokeep
RUN pip install --no-cache-dir "mutagen>=1.47,<2" "requests[socks]>=2.32,<3" "python-dotenv>=1,<2"
COPY docker-entrypoint.py runtime_config.py ./
COPY helpers/ ./helpers/
COPY --from=go-builder /out/melokeep /usr/local/bin/melokeep
COPY --from=frontend /build/frontend/dist /app/frontend/dist
COPY LICENSE NOTICE.md /licenses/
COPY --from=telegram-api /opt/telegram/bin/telegram-bot-api /usr/local/bin/telegram-bot-api
COPY --from=telegram-api /src/LICENSE_1_0.txt /licenses/LICENSE-telegram-bot-api
RUN telegram-bot-api --version
ENV HOME=/data/home TZ=Asia/Shanghai MELOKEEP_CONTAINER=1 MELOKEEP_STATIC_DIR=/app/frontend/dist MELOKEEP_PYTHON=/usr/local/bin/python3 MELOKEEP_PYTHON_BIN=/usr/local/bin/python3
EXPOSE 8765
VOLUME ["/music", "/data"]
ENTRYPOINT ["python", "/app/docker-entrypoint.py"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD curl -fsS http://127.0.0.1:8765/api/health || exit 1
CMD ["/usr/local/bin/melokeep"]
