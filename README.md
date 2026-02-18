# GFS — A Local Google File System Implementation

A local implementation of the [Google File System](https://static.googleusercontent.com/media/research.google.com/en//archive/gfs-sosp2003.pdf), built for deep understanding of distributed file system design.

## What is this?

This project implements the core architecture of GFS — the distributed file system that powered Google's infrastructure — on a single machine using gRPC over localhost. It faithfully reproduces the design decisions from the 2003 SOSP paper: chunked storage, single master metadata management, lease-based mutation ordering, operation logging, and replica management.

## Architecture

```
┌──────────────┐     ┌───────────────────┐     ┌───────────────────────────┐
│  Client CLI  │────▶│      Master       │────▶│      ChunkServers         │
│              │     │  (port 50051)     │     │  ┌─────┐ ┌─────┐ ┌─────┐ │
│  put, get,   │     │                   │     │  │50052│ │50053│ │50054│ │
│  ls, append  │     │  • Namespace      │     │  └──┬──┘ └──┬──┘ └──┬──┘ │
│              │     │  • Chunk mapping   │     │     │       │       │    │
└──────────────┘     │  • Lease mgmt     │     │  ┌──▼──┐ ┌──▼──┐ ┌──▼──┐ │
                     │  • Operation log   │     │  │disk │ │disk │ │disk │ │
                     └───────────────────┘     │  └─────┘ └─────┘ └─────┘ │
                                               └───────────────────────────┘
```

**Key design principle:** The master handles metadata only. Data flows directly between clients and chunkservers — the master is never in the data path.

## GFS Concepts Implemented

- **Chunked storage** — files split into 1MB chunks (configurable), each with a unique 64-bit handle
- **Single master** — all metadata in memory, persisted via write-ahead operation log + checkpoints
- **Leases** — primary chunkserver gets a time-limited lease to serialize mutations across replicas
- **Two-phase writes** — data pushed to all replicas first, then primary commits and orders mutations
- **Heartbeats** — chunkservers report health and chunk inventory; master piggybacks instructions
- **Replication** — each chunk stored on N chunkservers (default 3), re-replicated on failure
- **Chunk versioning** — detect stale replicas after chunkserver restarts
- **Lazy garbage collection** — deleted files renamed to hidden, chunks collected asynchronously
- **Snapshots** — instant copy-on-write file duplication

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin |
| Build | Gradle (Kotlin DSL) |
| IPC | gRPC over localhost |
| Serialization | Protocol Buffers |
| Process model | Single JVM, multiple gRPC servers on separate ports |

## Project Structure

```
gfs/
├── proto/                      # Protobuf service contracts
│   └── src/main/proto/gfs/
│       ├── core/               # Fundamental types (chunk, file, status, mutation, lease)
│       ├── master/             # Master service definitions + operation log
│       └── chunkserver/        # ChunkServer service definition
├── common/                     # Shared config and utilities
├── master/                     # Master server implementation
├── chunkserver/                # ChunkServer implementation
└── client/                     # Client library + CLI
```

## Building

```bash
./gradlew build
```

## Reference

- [The Google File System (2003)](https://static.googleusercontent.com/media/research.google.com/en//archive/gfs-sosp2003.pdf) — Ghemawat, Gobioff, and Leung
