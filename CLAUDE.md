# GFS — Local Google File System Implementation

## Project Purpose

This is a **learning-first implementation** of the Google File System, inspired by the [GFS paper (SOSP 2003)](https://static.googleusercontent.com/media/research.google.com/en//archive/gfs-sosp2003.pdf). The primary goal is **deep understanding**, not production readiness.

### The Golden Rule

> Every line of code must be understandable by the learner. If the learner (Saumya) cannot explain **why** a piece of code exists and **what GFS concept** it implements, the code has failed its purpose.

### End Goal

After completing this project, the learner should be able to:
1. **Design** a distributed file system from scratch on a whiteboard
2. **Explain** every GFS design decision and its trade-offs
3. **Rebuild** this entire system independently without reference
4. **Apply** the distributed systems patterns (leases, replication, WAL, heartbeats) to other systems
5. **Discuss** LLD choices — data structures, concurrency models, failure handling — at interview depth

## Teaching Requirements

### Every Module Must Include

- **Paper Reference**: Exact GFS paper section that inspired the component
- **Design Commentary**: Why Google made this choice, what alternatives exist, what breaks if you change it
- **Implementation Notes**: How our local version maps to the real distributed version
- **Rebuild Hint**: The one key insight needed to rebuild this component from scratch
- **LLD Callouts**: Data structure choices, concurrency patterns, and why they matter

### Code Commentary Style

- **KEEP CODE CLEAN** — no design commentary, no paper references, no trade-off discussions in code.
- Code should have minimal comments: only brief `// why` comments where the logic is genuinely non-obvious.
- **Design explanations happen in conversation**, not in files. After writing code, Claude explains the design decisions, GFS paper connections, trade-offs, and LLD concepts verbally in chat.
- Each package has a `WHY.md` explaining the design reasoning for that component (these are documentation files, not code).
- The code should read like clean, professional Kotlin — not a textbook.
- **Pause after each phase/chunk**: After writing a meaningful chunk of code, STOP and explain it conversationally before moving on. Cover: what was built, how it maps to GFS, design decisions, LLD concepts. Wait for the learner to acknowledge understanding before continuing.

### Abstraction Principles

- **Right level of abstraction**: Not too abstract (can't see what's happening), not too concrete (can't see the pattern). Every interface should map to a GFS concept.
- **No magic**: No frameworks that hide distributed systems concepts. We use gRPC directly, not wrapped in layers of abstraction.
- **Explicit over implicit**: State machines are explicit. Error handling is explicit. Concurrency is explicit.

## Architecture Decisions

### Locked In

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Kotlin | Learner's primary language — focus stays on GFS, not syntax |
| Build | Gradle (Kotlin DSL) | Standard Kotlin build tool |
| IPC | gRPC over localhost | Real network boundaries, mirrors actual GFS communication |
| Serialization | Protocol Buffers | What GFS actually used; teaches schema-first API design |
| Process Model | Single JVM, multiple gRPC servers on separate ports | Simulates distributed processes, easy to manage and "kill" |
| Chunk Size | 1MB default (configurable) | Small enough to exercise multi-chunk logic with test files |
| Replication | 3 replicas default | Matches GFS default |

### GFS Components We're Building

| Component | Port | Role |
|-----------|------|------|
| Master | 50051 | Namespace, file-to-chunk mapping, leases, coordination |
| ChunkServer-1 | 50052 | Stores chunks, serves reads, applies mutations |
| ChunkServer-2 | 50053 | Same |
| ChunkServer-3 | 50054 | Same |
| Client CLI | — | User-facing commands: put, get, ls, mkdir, delete, append |

### Key GFS Concepts to Implement

1. **Chunking**: Files split into fixed-size chunks, each with a globally unique 64-bit handle
2. **Single Master**: All metadata in memory, persisted via operation log + checkpoints
3. **Leases**: Primary chunkserver gets a time-limited lease to serialize mutations
4. **Operation Log (WAL)**: Master's source of truth — append-only, checkpointed periodically
5. **Heartbeats**: ChunkServers report health + chunk inventory to Master
6. **Replication**: Each chunk stored on N chunkservers; re-replicated on failure
7. **Consistency Model**: Defined (all replicas same) vs Consistent (clients see same) vs Undefined
8. **Garbage Collection**: Lazy deletion — files renamed to hidden, chunks collected asynchronously
9. **Chunk Version Numbers**: Detect stale replicas after chunkserver restarts
10. **Data Flow vs Control Flow**: Data pushed through chunkserver pipeline; write order controlled by primary

## Implementation Phases

### Phase 1: Foundation & Protobuf Contracts
- Project structure, Gradle setup, protobuf definitions
- All RPC service definitions: Master, ChunkServer, Client interfaces

### Phase 2: Master Core
- In-memory namespace (tree)
- File-to-chunk mapping
- Operation log + checkpoint + recovery

### Phase 3: ChunkServer Core
- Chunk storage on disk
- Heartbeat to master
- Read serving + checksum verification

### Phase 4: Write Path + Leases
- Lease management on master
- Primary election, mutation ordering
- Data push pipeline, write + record append

### Phase 5: Fault Tolerance
- Failure detection (missed heartbeats)
- Re-replication, garbage collection
- Stale replica detection via version numbers

### Phase 6: CLI + Integration
- Full CLI tool
- Snapshot (copy-on-write) if time permits
- End-to-end integration tests

## Code Quality Standards

### Design Practices (Tech Lead / Engineer lens)

- **Single Responsibility**: Each class does one thing. A `LeaseManager` manages leases, not chunks.
- **Interface-first**: Define contracts before implementations. Every gRPC service has a corresponding Kotlin interface.
- **Immutable by default**: Data classes are immutable. State changes go through explicit state machines.
- **Fail-fast and explicit**: No silent swallowing of errors. Failures propagate with context.
- **Testable**: Every component can be tested in isolation. Dependencies injected, not hardcoded.
- **Naming matters**: Names come from GFS paper terminology. `ChunkHandle`, `LeaseGrant`, `MutationOrder` — not generic names.

### What We Intentionally Skip

- Security / authentication (not the learning goal)
- Multi-master / high availability of master (GFS paper scope = single master)
- Production error handling for edge cases beyond GFS scope
- Performance optimization beyond what the paper describes

## LLD Concepts to Highlight Throughout

- **Write-Ahead Logging**: Why you log before you act
- **State Machines**: Chunk lifecycle, lease lifecycle
- **Consistent Hashing** (if applicable) vs Master-directed placement
- **Heartbeat Protocol Design**: Distributed failure detection
- **Concurrency Control**: Leases as distributed locks, sequence numbers for ordering
- **Copy-on-Write**: Snapshot mechanism
- **Checksumming**: Data integrity at rest and in transit
- **Idempotency**: Why record append is "at least once" and how clients handle duplicates
