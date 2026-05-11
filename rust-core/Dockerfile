# syntax=docker/dockerfile:1
# Multi-stage build for router-ready musl binary

# ---- Builder ----
FROM rust:1.84-alpine AS builder
RUN apk add --no-cache musl-dev openssl-dev pkgconfig

WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src

# Build with musl target (statically linked, runs on any Linux)
RUN cargo build --release

# ---- Runtime ----
FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata

COPY --from=builder /app/target/release/stalkerhek-engine /usr/local/bin/stalkerhek

ENV STALKERHEK_BIND_ADDR=0.0.0.0:9900

EXPOSE 9900 4600 4800

VOLUME ["/data"]
WORKDIR /data

ENTRYPOINT ["/usr/local/bin/stalkerhek"]
