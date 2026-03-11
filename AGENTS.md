# AGENTS.md — Stockfish.JSWT

Guidelines for AI coding agents working in this repository.

---

## Project Overview

**Stockfish.JSWT** is a Java 25 desktop chess application using Eclipse SWT for the UI and a
Stockfish WASM engine (run inside GraalVM's Polyglot JS context) as the AI backend. Build
tooling is Gradle 9.4.0 (Groovy DSL).

---

## Build Commands

```bash
# Compile and run all tests
./gradlew build

# Compile only (no tests)
./gradlew classes

# Run the application
./gradlew run

# Run all tests
./gradlew test

# Build a fat JAR
./gradlew jar

# GraalVM native image (requires GraalVM JDK 25)
./gradlew nativeCompile
./gradlew nativeRun

# Performance benchmark
./gradlew benchmark
```

> **Windows:** Use `gradlew.bat` (or just `gradlew`) instead of `./gradlew`.

---

## Running Tests

**Framework:** JUnit Jupiter (JUnit 5), version 6.0.3.
**Test sources:** `src/test/java/io/github/seerainer/chess/test/`

```bash
# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "io.github.seerainer.chess.test.UCIProtocolTest"

# Run a single test method
./gradlew test --tests "io.github.seerainer.chess.test.UCIProtocolTest.testParseBestMoveSimple"

# Wildcard class match
./gradlew test --tests "*.UCIProtocolTest"
```

Tests run in parallel (`maxParallelForks = availableProcessors()`), have a 10-minute timeout,
and are allocated a 256 MB–4 GB heap. Some tests use `assumeTrue` to skip gracefully when the
Stockfish WASM resource is unavailable (e.g., in CI without GraalVM).

**Test classes (3 total):**
- `UCIProtocolTest` — pure unit tests, no engine required; reference example for style
- `StockfishEngineTest` — integration tests with `assumeTrue` guards for WASM availability
- `StockfishSearchEngineTest` — end-to-end tests with `@Nested` grouping

---

## No Dedicated Lint / Formatter

There is no Checkstyle, SpotBugs, PMD, Spotless, or other lint Gradle plugin configured. Code
style is enforced by convention and Eclipse JDT compiler warnings
(`.settings/org.eclipse.jdt.core.prefs`). Notable JDT rules that are treated as **errors**:
- `forbiddenReference` (forbidden API access)
- `nullSpecViolation` and `nullAnnotationInferenceConflict` (null-safety annotations)

The following are **warnings** (and should not be introduced):
- Unused imports, unused locals, unused private members
- Raw type references
- Deprecation usage

---

## Java Version and Toolchain

- **Java 25** (Oracle JDK or GraalVM JDK 25)
- The Gradle build declares `JavaLanguageVersion.of(25)` enforced via foojay toolchain resolver
- Use Java 25 language features freely: records, pattern-matching switch expressions,
  sequenced collections, `final var`, text blocks, etc.

---

## Code Style

### Indentation and Formatting

- **Hard tabs** for indentation (one tab per level) — Eclipse JDT default style
- Opening brace on the **same line** as the declaration (K&R style)
- Single blank line between methods
- No trailing whitespace

### Imports

- Explicit single-type imports only — **no wildcard imports**
- Static imports used selectively for readability (e.g., SWT listeners, JUnit assertions)
- Import order: `java.*` → `javax.*` → third-party (`org.*`, `com.*`) → internal (`io.github.seerainer.*`)
- Remove all unused imports

### Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Classes / Interfaces | `UpperCamelCase` | `StockfishEngine`, `UCIProtocol` |
| Methods | `lowerCamelCase` | `getBestMove`, `parseUCIMove` |
| Constants (`static final`) | `UPPER_SNAKE_CASE` | `CMD_UCI`, `DEPTH_SEARCH_TIMEOUT_MS` |
| Local variables | `lowerCamelCase` with `final var` | `final var result = ...` |
| Fields (instance) | `lowerCamelCase` | `outputQueue`, `initSucceeded` |
| Parameters | `lowerCamelCase`, `final` where possible | `final int depth` |
| Packages | lowercase, dot-separated | `io.github.seerainer.chess.stockfish` |
| Config nested classes | `UpperCamelCase` with `UPPER_SNAKE_CASE` fields | `ChessConfig.Stockfish.DEPTH` |

### Variable Declarations

- Use `final var` for all local variables where type can be inferred
- Apply `final` to method parameters and instance fields wherever semantically correct
- Use `volatile` for fields shared between threads (e.g., `outputListener`, `lastSearchInfo`)
- Use `AtomicBoolean` / `AtomicInteger` for lock-free shared counters and flags (`initialized`, `closed`)

### Types and Language Features

- Prefer **records** for immutable data-only types (e.g., `BestMoveResult`, `SearchInfo` in `UCIProtocol`)
- Use **switch expressions** with arrow cases for pattern matching over multiple values
- Use `String.formatted(...)` rather than `String.format(...)`
- Use the **Stream API** for collection transforms and filtering
- Use `CompletableFuture` for async operations
- Use try-with-resources for all `AutoCloseable` objects
- Prefer `List.of(...)`, `Map.of(...)`, `Set.of(...)` for immutable collections

### Error Handling

- Catch exceptions at the **boundary** (UI handler, async callback, engine interface), not deep inside algorithms
- Use `System.err.println(...)` and `e.printStackTrace()` for non-fatal errors in AI/engine code
- Provide **fallback logic**: if Stockfish WASM fails → log and return `null`; callers handle the null gracefully
- Null-check move results before use (`if (result == null || result.move() == null)`)
- Wrap checked exceptions in `RuntimeException` only inside lambda/stream contexts where unchecked is required
- Never silently swallow exceptions; the one deliberate exception is `NumberFormatException` inside the
  UCI `parseInfo` token loop (malformed token skipped, not logged, as it is non-critical noise)
- Always re-set the interrupt flag after catching `InterruptedException`:
  `Thread.currentThread().interrupt()`
- Use `AtomicBoolean.compareAndSet(false, true)` for idempotent `close()` guards

### Javadoc

- All `public` classes and methods must have Javadoc
- Include `@param` and `@return` tags for non-trivial parameters and return values
- Class-level Javadoc should describe purpose and responsibilities
- Use inline `// comment` for implementation-level explanations

---

## Architecture Patterns

Follow these patterns established in the codebase; do not introduce conflicting patterns.

### Configuration

All constants live in `ChessConfig` (`src/main/java/io/github/seerainer/chess/config/ChessConfig.java`).
It is a non-instantiable class with `public static final` nested classes: `Debug`, `Memory`,
`Rules`, `Stockfish`, `UI`. Add new constants here rather than scattering magic numbers in
algorithm code. Runtime overrides are persisted via `AppPreferences` (wraps
`java.util.prefs.Preferences`).

### Layered AI Architecture

```
ChessGameUI  (SWT UI controller)
  └── ChessAI  (facade, owns async worker via ResourceManager)
        └── StockfishSearchEngine  (high-level UCI search, config params)
              └── StockfishEngine  (low-level GraalVM Polyglot bridge)
                    └── GraalJS Context + stockfish.wasm
```

### SWT UI Thread Model

- All SWT widget access **must** run on the display thread
- Use `display.asyncExec(() -> { ... })` to dispatch AI results back to the UI
- Compute AI moves asynchronously via `CompletableFuture`; never block the SWT event thread

### GraalVM / Stockfish Bridge

- `StockfishEngine` runs on a dedicated single-thread executor named `"Stockfish-Engine"`;
  the GraalVM `Context` is not thread-safe and must never be accessed from other threads
- Java → JS calls use `ProxyExecutable` bound as JS globals (`__javaOutputHandler`, `__wasmBinary`)
- JS → Java output is routed through `__javaOutputHandler` into a `BlockingQueue<String>`
- `waitForResponse(prefix, timeoutMs)` polls the queue every 100 ms with a configurable timeout
- `go` commands are submitted fire-and-forget (WASM `ccall` is synchronous and blocks the engine
  thread); all other commands are awaited synchronously to preserve ordering

### Resource Management

- `ResourceManager` owns the `Chess-AI-Worker` `ExecutorService` with proper `shutdown()` /
  `awaitTermination()` / `shutdownNow()` cascade
- Both `StockfishEngine` and `StockfishSearchEngine` implement `AutoCloseable`
- SWT `Color` and `Font` objects are disposed in `ChessBoard.dispose()` to prevent GDI leaks

---

## Project Layout Reference

```
src/
  main/java/io/github/seerainer/chess/
    Main.java                  # SWT entry point and event loop
    ChessAI.java               # AI facade; delegates to StockfishSearchEngine
    ChessBoard.java            # SWT Canvas rendering and mouse input
    ChessGameUI.java           # Shell, menus, game controller
    SettingsDialog.java        # Modal SWT settings dialog
    config/
      ChessConfig.java         # All compile-time constants (nested classes)
      AppPreferences.java      # Persistence via java.util.prefs.Preferences
    stockfish/
      StockfishEngine.java     # Low-level GraalVM Polyglot bridge
      StockfishSearchEngine.java  # High-level UCI search with configurable params
      UCIProtocol.java         # UCI command building, response parsing, records
    utils/
      ResourceManager.java     # ExecutorService lifecycle management
  main/resources/
    stockfish/                 # stockfish-18-lite-single.wasm, stockfish-18-lite-single.js, bridge JS
    META-INF/                  # native-image reachability metadata
  test/java/io/github/seerainer/chess/test/
    UCIProtocolTest.java       # Reference style example; use @Nested grouping
    StockfishEngineTest.java   # Uses assumeTrue for optional WASM dependency
    StockfishSearchEngineTest.java  # End-to-end tests with @Nested groups
```

---

## Testing Conventions

- Annotate every test with `@DisplayName("descriptive sentence")`
- Group related tests in `@Nested` inner classes (see `UCIProtocolTest` as the reference)
- Use `@Timeout(value = 60, unit = TimeUnit.SECONDS)` on all tests involving search or I/O
- Use `org.junit.jupiter.api.Assumptions.assumeTrue(...)` to skip tests requiring unavailable
  resources (e.g., WASM engine in CI without GraalVM)
- Use `@BeforeEach` / `@AfterEach` for engine lifecycle (create → test → close)
- Do not use JUnit 4 annotations — `@Test` must be from `org.junit.jupiter.api`
- All assertion imports are static: `import static org.junit.jupiter.api.Assertions.*`
