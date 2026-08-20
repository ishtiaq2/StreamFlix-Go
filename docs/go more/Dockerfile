# --- build & test stage ---
FROM golang:1.22-alpine AS builder
WORKDIR /src
COPY go.mod ./
COPY internal/ ./internal/
COPY cmd/ ./cmd/

# This is the whole point of putting `go test` in the Dockerfile: a broken
# test fails the image build, the same gate a real CI pipeline enforces --
# no image ever gets built (let alone deployed) from code that fails its
# own tests.
RUN go test ./...
RUN go build -o /out/auth-service ./cmd/authsvc

# --- runtime stage ---
FROM alpine:3.20
RUN adduser -D -H appuser
COPY --from=builder /out/auth-service /usr/local/bin/auth-service
USER appuser
EXPOSE 8083
ENTRYPOINT ["/usr/local/bin/auth-service"]
