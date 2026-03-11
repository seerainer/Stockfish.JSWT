# ♟️ Stockfish.JSWT

A Java 25 desktop chess application powered by the [Stockfish](https://stockfishchess.org/) engine. The UI is built with [Eclipse SWT](https://www.eclipse.org/swt/) and the engine runs as a WebAssembly binary inside a [GraalVM](https://www.graalvm.org/) Polyglot JavaScript context — no native process, no external installation required.

---

## ✨ Features

- ♟️ **Full chess rules** — legal move generation, check/checkmate/stalemate/draw detection, threefold-repetition and 50-move-rule enforcement
- 🤖 **Stockfish WASM engine** — the same engine used by chess.com and lichess, embedded directly in the JAR via GraalVM's Polyglot JS/WASM runtime
- 🎮 **Three game modes** — Human vs Human, Human vs AI (play as White or Black), Computer vs Computer
- ⚙️ **Configurable engine strength** — skill level (0–20), think time, search depth, UCI ELO limiting
- 💾 **Persistent settings** — all preferences are saved across sessions using the Java `Preferences` API (no config files to manage)
- 🪟 **Settings dialog** — a clean modal dialog exposes every engine and UI option at runtime (Engine › Settings… or `Ctrl+,`)
- 🧹 **Memory management** — background cleanup thread trims position history and monitors heap usage
- 🚀 **GraalVM native image** support for a fast-starting, self-contained executable

---

## 📦 Dependencies

| Library | Description |
|---|---|
| [Eclipse SWT](https://www.eclipse.org/swt/) | Native desktop UI widgets |
| [bhlangonijr/chesslib](https://github.com/bhlangonijr/chesslib) | Legal move generation, FEN parsing, board state |
| [nmrugg/stockfish.js](https://github.com/nmrugg/stockfish.js) | Stockfish chess engine compiled to WebAssembly — the WASM binary bundled in `src/main/resources/stockfish/` is built from this project (currently Stockfish 18 / single-threaded) |
| [GraalVM Polyglot](https://www.graalvm.org/latest/reference-manual/polyglot-programming/) | Runs the Stockfish JS/WASM bundle inside a Java process via a GraalJS `Context` |

> 🔖 **stockfish.js** is a WASM port of the [official Stockfish engine](https://github.com/official-stockfish/Stockfish) by Nathan Rugg, sponsored by Chess.com. It is the same engine that powers in-browser analysis on chess.com and lichess. Licensed under **GPLv3**.

---

## 🔧 Requirements

| Requirement | Version |
|---|---|
| ☕ JDK | Oracle JDK 25 or GraalVM JDK 25 |
| 🖥️ OS | Windows, macOS, Linux (x86-64 or aarch64) |
| 🔨 Build tool | Gradle (wrapper included) |

> GraalVM JDK 25 is required only for the native image target (`nativeCompile`). The standard `run` and `jar` tasks work with any JDK 25.

---

## 🏗️ Building and running

All commands use the Gradle wrapper. On Windows substitute `./gradlew` with `gradlew` or `gradlew.bat`.

```bash
# Run the application
./gradlew run

# Compile only (no tests)
./gradlew classes

# Run all tests
./gradlew test

# Build a fat JAR (includes SWT and all dependencies)
./gradlew jar

# GraalVM native image — requires GraalVM JDK 25
./gradlew nativeCompile
./gradlew nativeRun

# Performance benchmark
./gradlew benchmark
```

---

## 🧪 Running tests

Tests use JUnit Jupiter (JUnit 5) and run in parallel.

```bash
# All tests
./gradlew test

# Single test class
./gradlew test --tests "io.github.seerainer.chess.test.UCIProtocolTest"

# Single test method
./gradlew test --tests "io.github.seerainer.chess.test.UCIProtocolTest.testParseBestMoveSimple"

# Wildcard match
./gradlew test --tests "*.StockfishEngineTest"
```

Tests that require the Stockfish WASM resource use `assumeTrue` to skip gracefully when the resource is unavailable (e.g. in CI without GraalVM).

---

## 🗺️ Architecture overview

```
src/main/java/io/github/seerainer/chess/
├── Main.java                   SWT entry point and event loop
├── ChessGameUI.java            Shell, menus, game controller, memory management
├── ChessBoard.java             SWT Canvas — piece rendering and mouse input
├── ChessAI.java                AI facade — delegates to StockfishSearchEngine
├── SettingsDialog.java         Modal SWT settings dialog
├── config/
│   ├── ChessConfig.java        All compile-time constants
│   └── AppPreferences.java     Java Preferences API persistence layer
├── stockfish/
│   ├── StockfishEngine.java    GraalVM Polyglot bridge (JS + WASM context)
│   ├── StockfishSearchEngine.java  High-level UCI search with configurable params
│   └── UCIProtocol.java        UCI command building and response parsing
└── utils/
    └── ResourceManager.java    Shared ExecutorService lifecycle
```

### 🔌 Engine integration

Stockfish runs as a WebAssembly module loaded by `stockfish-graaljs-bridge.js` inside a GraalVM `Context`. All GraalJS operations execute on a dedicated single thread (`Stockfish-Engine`) because `Context` is not thread-safe. The WASM `ccall` for search commands is **synchronous** — it blocks the engine thread for the full duration of the search. Java communicates with the engine through:

- **Java → JS**: `ProxyExecutable` references bound as JS globals (`__javaOutputHandler`, `__wasmBinary`)
- **JS → Java**: output lines are routed through `__javaOutputHandler` into a `BlockingQueue<String>`
- **Waiting for results**: `StockfishEngine.waitForResponse` polls the queue with a timeout derived from the configured search budget plus a fixed overhead (`MOVE_TIMEOUT_OVERHEAD_MS`)

### 💿 Settings persistence

`AppPreferences` wraps `java.util.prefs.Preferences` (user node, keyed under the `ChessConfig` package). Every engine and UI setting has a typed `load*` / `save*` pair. Settings are applied to the live engine immediately when the dialog is confirmed — no restart required.

---

## ⚙️ Configuration reference

All compile-time defaults live in `ChessConfig`. Runtime values are stored by `AppPreferences` and override the defaults on next launch.

| Setting | Default | Description |
|---|---|---|
| `Stockfish.SKILL_LEVEL` | `10` | Engine strength 0 (weakest) – 20 (strongest) |
| `Stockfish.THINK_TIME_MS` | `3000` | Search time budget per move in ms |
| `Stockfish.DEPTH` | `20` | Maximum search depth in half-moves |
| `Stockfish.LIMIT_STRENGTH` | `true` | Cap engine strength at UCI ELO |
| `Stockfish.UCI_ELO` | `1500` | Target ELO when strength limiting is on |
| `Stockfish.HASH_SIZE_MB` | `128` | Transposition table size in MB |
| `Stockfish.MOVE_TIMEOUT_OVERHEAD_MS` | `5000` | Extra wait added on top of think time |
| `Stockfish.DEPTH_SEARCH_TIMEOUT_MS` | `300000` | Ceiling for depth-only searches (5 min) |
| `UI.BOARD_SIZE` | `640` | Board canvas size in pixels |
| `UI.AI_MOVE_DELAY_MS` | `300` | Pause between moves in Computer vs Computer mode |

---

## 📄 License

See [LICENSE.txt](LICENSE.txt).
