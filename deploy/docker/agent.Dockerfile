# Tag pinned after 2026-09-21 local rustc 1.98.1 verification. Record image digest before production.
FROM rust:1.98.1-slim-bookworm AS build
WORKDIR /workspace
COPY Cargo.toml Cargo.lock ./
COPY apps/agent-runtime apps/agent-runtime
COPY contracts contracts
COPY extensions/skills/incident-diagnosis extensions/skills/incident-diagnosis
RUN cargo build --locked --release -p opsweave-agent-runtime

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/release/opsweave-agent-runtime /app/agent-runtime
COPY extensions/skills/incident-diagnosis /app/extensions/skills/incident-diagnosis
ENV OPSWEAVE_MODE=closed OPSWEAVE_LISTEN=0.0.0.0:8090
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["/app/agent-runtime"]
