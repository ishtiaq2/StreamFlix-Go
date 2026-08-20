# Learning Path

There's a right order to go through this repo in — it's built as one continuous
argument, not a pile of unrelated examples. This page tells you where to start and
what to expect at each stage.

## The shape of the whole thing

```
01-BASE-STACK-GUIDE.md        →  the 7-container platform: architecture, Java, Python, containers
02-GO-DEVICE-GATEWAY-GUIDE.md →  add Go: concurrency + an event bus
03-GO-AUTH-SERVICE-GUIDE.md   →  add more Go: identity, RBAC, testing
```

Each guide assumes you've finished the one before it and the stack from it is
running. Don't skip to Go before the base stack works — half of what makes the Go
chapters make sense is seeing how `device-gateway` and `auth-service` fit into an
architecture you already understand.

## If you're new to containers

Read `docs/GLOSSARY.md`'s "Containers & architecture" section first, then start
`01-BASE-STACK-GUIDE.md` at Part 1. Don't rush Part 3 (the database) — everything
else depends on that schema being clear in your head before you look at any service
code.

## If you already know containers but are new to Go

Skim `01-BASE-STACK-GUIDE.md`'s architecture section (Part 1) and repo layout (Part
2) so you know what's already built, then jump straight to
`02-GO-DEVICE-GATEWAY-GUIDE.md`. Read the "Go / device-gateway, auth-service" section
of the glossary alongside it — every concurrency term it introduces (goroutine,
channel, worker pool, `select`, `context`) is used, in order, in that guide's code
walkthrough.

## If you already know Go but are new to this kind of architecture

Read `01-BASE-STACK-GUIDE.md` in full for the architecture reasoning (Parts 1, 12,
15 especially — the "why split into services this way" thinking), but move quickly
through the Java/Python code sections. Then both Go guides will read fast, since
you'll already know the language and just be picking up how it's applied here.

## If you want the fastest possible path to something running

```bash
cd streamflix-stack
podman-compose up -d --build
```

Then come back and read the guides against the running system — pull up `/alarms`,
`/devices/status`, or `/me` in one terminal while reading the section that explains
what produced that response in another. Concepts land faster when there's a live
system to poke at while reading.

## After all three guides

You'll have covered, in order: container architecture, a Java REST service, a Python
REST service, load balancing, chaos engineering, an external monitoring interface,
Go concurrency primitives, event-driven architecture, and identity/authorization —
each one built by hand, not just described. From here, the natural next moves (each
guide ends with a "what's next" section) are: persisting `device-gateway`'s and
`core-engine`'s in-memory state, wiring `auth-service`'s tokens into the other
services so authorization is enforced end-to-end, and adding a CI pipeline that runs
`go test ./...` and the Java/Python equivalents on every push.
