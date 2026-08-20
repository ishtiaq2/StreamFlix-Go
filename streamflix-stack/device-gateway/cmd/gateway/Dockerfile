# --- build stage ---
FROM golang:1.22-alpine AS builder
WORKDIR /src
COPY go.mod ./
COPY cmd/ ./cmd/
RUN go build -o /out/device-gateway ./cmd/gateway

# --- runtime stage ---
FROM alpine:3.20
RUN adduser -D -H appuser
COPY --from=builder /out/device-gateway /usr/local/bin/device-gateway
USER appuser
EXPOSE 8082
ENTRYPOINT ["/usr/local/bin/device-gateway"]