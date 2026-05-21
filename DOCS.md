# BlueJ-Greenfoot — Project Documentation

> **Version** 5.5.0 (BlueJ) / 3.9.0 (Greenfoot) · **Java** 21 · **JavaFX** 23.0.2 · **Build** Gradle 8.5

---

## Contents

1. [Project Overview](#1-project-overview)
2. [Repository Layout](#2-repository-layout)
3. [Build System](#3-build-system)
4. [Startup Sequence](#4-startup-sequence)
5. [Configuration](#5-configuration)
6. [Package Manager](#6-package-manager)
7. [Compiler](#7-compiler)
8. [Debugger](#8-debugger)
9. [Editor System](#9-editor-system)
   - [9.1 Interfaces](#91-interfaces)
   - [9.2 Flow Editor (Java text)](#92-flow-editor-java-text)
   - [9.3 Text Rendering Stack](#93-text-rendering-stack)
   - [9.4 Stride Editor (block-based)](#94-stride-editor-block-based)
   - [9.5 Quick Fixes](#95-quick-fixes)
10. [Parser](#10-parser)
   - [10.1 Node hierarchy](#parse-tree-node-hierarchy)
   - [10.2 Node API reference](#nodeandpositiont--position-aware-node-wrapper)
   - [10.3 Parent–child relationships](#parentchild-relationships-in-the-parse-tree)
11. [Class & Type System](#11-class--type-system)
12. [Extensions API](#12-extensions-api)
13. [Version Control](#13-version-control)
14. [Preferences](#14-preferences)
15. [Testing Infrastructure](#15-testing-infrastructure)
16. [Threading Model](#16-threading-model)
17. [Key Design Patterns](#17-key-design-patterns)
18. [Data Flow Diagrams](#18-data-flow-diagrams)
19. [Code Outline Sidebar (Custom Feature)](#19-code-outline-sidebar-custom-feature)

---

## 1. Project Overview

BlueJ and Greenfoot are open-source educational Java IDEs sharing a single monorepo.

- **BlueJ** — a full IDE aimed at beginner Java programmers. Features a UML-style package diagram, interactive object bench, integrated debugger, and two editors: a traditional text editor and a block-based "Stride" editor.
- **Greenfoot** — a game/simulation framework built on top of BlueJ. Students create interactive programs by subclassing `Actor` and `World`.

Both applications compile from the same Gradle build and share the `bluej` module. A runtime flag (`Config.isGreenfoot()`) switches between the two modes.

---

## 2. Repository Layout

```
bluej-greenfoot/
├── build.gradle            Root build — Java release target, shared version props
├── settings.gradle         Declares all sub-projects
├── version.properties      Single source of truth for all version numbers
├── tools.properties        Paths to external tools (Ant, MingW, WiX)
├── gradle.properties       Flatpak output directory
│
├── boot/                   Bootstrap module — entry point & class-loader setup
├── bluej/                  Main application module
│   ├── build.gradle
│   ├── lib/                Bundled assets: images, stylesheets, config files, native libs
│   └── src/main/java/bluej/
│       ├── Boot.java       (lives in boot/) — see §4
│       ├── Main.java       Real init after class-loader setup
│       ├── Config.java     Global configuration singleton
│       ├── pkgmgr/         Package & project management
│       ├── compiler/       Java compilation layer
│       ├── debugger/       Debugger abstraction + JDI implementation
│       ├── editor/         All editor implementations
│       ├── parser/         Java & Stride parser
│       ├── extensions2/    Plugin/extension API
│       ├── groupwork/      Version control (Git/SVN/CVS)
│       ├── prefmgr/        User preference management
│       ├── testmgr/        JUnit test runner integration
│       ├── classmgr/       Custom class-loader management
│       ├── debugmgr/       Interactive invocation & object bench
│       ├── runtime/        ExecServer — runs inside the debug VM
│       ├── collect/        Anonymized usage data collection
│       ├── graph/          UML diagram drawing
│       ├── terminal/       I/O console
│       ├── utility/        Shared utilities (JavaFX helpers, dialogs, Debug)
│       └── views/          Reflective view API (methods, constructors)
│
├── greenfoot/              Greenfoot-specific module
├── lang-stride/            Stride language AST and Java code generation
├── threadchecker/          Compile-time thread-safety checker (Gradle plugin)
└── anns-threadchecker/     @OnThread annotation definitions
```

---

## 3. Build System

### Sub-project dependency graph

```
anns-threadchecker   ← annotation definitions only
threadchecker        ← Gradle plugin; annotation processor
boot                 ← no project deps
lang-stride          ← no project deps
bluej                ← boot, lang-stride, anns-threadchecker, threadchecker
greenfoot            ← bluej
```

### Java version

All sub-projects compile to **Java 21 bytecode** via:

```groovy
// root build.gradle
allprojects {
    tasks.withType(JavaCompile) {
        options.encoding = 'UTF-8'
        options.release = 21
    }
}
```

> Without `allprojects {}`, sub-projects built with a newer JDK would produce incompatible bytecode and fail Gradle's variant resolution when `bluej` requests Java 21-compatible libraries.

### Key Gradle tasks

| Task | What it does |
|---|---|
| `compileJava` | Compiles all sources; runs `threadchecker.TCPlugin` annotation processor |
| `recordCommitID` | Writes `git rev-parse --short=8 HEAD` → `lib/buildid.txt` |
| `jar` | Produces `build/libs/bluej.jar` (all classes, excludes `lib/` dir) |
| `blueJCoreJar` | Produces `src/main/resources/lib/bluejcore.jar` — all classes **except** `bluej/extensions2/**` and `bluej/editor/**` (loaded dynamically at runtime) |
| `copyToLib` | Stages all runtime jars + `bluej/lib/` assets into `src/main/resources/lib/` |
| `assemble` | `blueJCoreJar` + `copyToLib` (both run before packaging) |
| `runBlueJ` | `assemble` then `java -cp <runtimeClasspath> bluej.Boot -bluej.debug=true` |
| `test` | Headless JavaFX tests via Monocle |
| `packageBlueJMac[Intel\|Aarch]` | Ant-driven macOS `.app` bundles |
| `packageBlueJWindows` | Ant + WiX MSI installer |
| `packageBlueJLinux` | Ant `.deb` + source tarball |
| `flatpakGradleGenerator` | Generates Flatpak dependency manifest |

### Runtime staging

`copyToLib` assembles the runtime inside `src/main/resources/lib/`:

```
src/main/resources/lib/
  bluej.jar              main application jar
  bluejcore.jar          core-only jar (no editor, no extensions)
  buildid.txt            git short hash
  guava-*.jar
  jgit-*.jar             (and all other runtimeClasspath jars)
  <everything from bluej/lib/>   images, stylesheets, config, native libs
```

---

## 4. Startup Sequence

```
java bluej.Boot (boot module)
  │
  ├─ Parses command-line args into commandLineProps
  ├─ Constructs runtimeClassPath (finds JARs relative to bluej.lib.dir)
  ├─ Builds a URLClassLoader over runtimeClassPath
  ├─ Displays splash screen
  └─ Application.launch(App.class)  →  App.start(Stage)
        │
        └─ new Main(runtimeClassLoader)
              │
              ├─ Config.initialise(bluejLibDir, cmdProps, isGreenfoot)
              │     loads: bluej.defs, greenfoot.defs, ~/.bluej/bluej.properties,
              │            moe.defs, ~/.bluej/moe.properties
              │
              ├─ loads GuiHandler dynamically:
              │     BlueJGuiHandler  (BlueJ mode)
              │     GreenfootGuiHandler  (Greenfoot mode)
              │
              ├─ prepareMacOSApp()  (macOS only — dock icon, menu bar)
              │
              ├─ fetchAndShowCentralMsg()  (background — server message from bluej.org)
              │
              ├─ processArgs(args)  on FX thread:
              │     opens projects from command line, or
              │     re-opens orphaned packages from last session
              │
              └─ updateStats()  (background — anonymised usage data)
```

**`Boot.java`** lives in the `boot/` module to keep the bootstrap class-loader logic separate from the main application. It knows how to find `bluej.lib.dir`, construct the full classpath, and hand control to `Main`.

**`Main.java`** performs real initialisation. It is responsible for loading the correct GUI handler (BlueJ vs Greenfoot), setting up macOS integration, and restoring the previous session.

---

## 5. Configuration

**File:** `bluej/src/main/java/bluej/Config.java` (~1 750 lines)

### Property hierarchy (later entries override earlier)

| Layer | File | Purpose |
|---|---|---|
| System | `bluej.defs` | Shipped defaults |
| Greenfoot | `greenfoot.defs` | Greenfoot-specific defaults |
| User | `~/.bluej/bluej.properties` | User overrides |
| Command | `-Dproperty=value` args | Highest priority |
| Editor system | `moe.defs` + `~/.bluej/moe.properties` | Editor-specific settings |

### Key responsibilities

- **Language detection** — auto-detects locale or reads `bluej.language` property; loads `labels` file for UI strings.
- **User home** — creates `~/.bluej/` on first run; configurable via `bluej.userHome`.
- **Screen bounds** — stores `screenBounds` for dialog positioning.
- **Stylesheet loading** — `addEditorStylesheets(Scene)` loads all CSS files from `lib/stylesheets/` for every editor window.
- **Exit handling** — `handleExit()` flushes all properties to disk.

### Important constants

| Constant | Meaning |
|---|---|
| `BLUEJ_OPENPACKAGE` | Property key storing orphaned open packages for session restore |
| `MESSAGE_LATEST_SEEN` | Date of last displayed server message |
| `EDITOR_COUNT_JAVA` / `EDITOR_COUNT_STRIDE` | Per-session editor open counts |
| `PROJECT_CHARSET_PROP` | Character set for source files in a project |

---

## 6. Package Manager

**Package:** `bluej.pkgmgr`

The package manager is the central coordinator. It owns projects, packages, class targets, compilation, and execution.

### Class hierarchy

```
Project                     one per open directory
  └── Package (1..*)         one per Java package within the project
        └── Target (1..*)    one per file/class shown in the diagram
              ├── ClassTarget        .java / .class file
              ├── TextFileTarget     arbitrary text file
              ├── ReadmeTarget       README.TXT
              ├── ParentPackageTarget  ".." nav node
              └── CSSTarget          .css file (Greenfoot)
```

### `Project.java`

Central object representing an open BlueJ project.

| Field | Type | Purpose |
|---|---|---|
| `projectDir` | `File` | Root directory |
| `packages` | `Map<String, Package>` | All open packages |
| `debugger` | `Debugger` | Debug VM for this project |
| `execControls` | `ExecControls` | Run/pause/debug UI |
| `terminal` | `Terminal` | Console for output |
| `inspectors` | `Map` | Open inspector windows |
| `currentClassLoader` | `BPClassLoader` | Loads user classes |
| `teamSettingsController` | `TeamSettingsController` | VCS integration |

**Key static methods:**

| Method | Description |
|---|---|
| `openProject(File)` | Open a project; returns `Project` or null |
| `getProjects()` | All currently open projects |
| `isProject(String)` | Check if a path contains a valid BlueJ project |

### `Package.java`

Represents one Java package. Loads and saves the `.bluej` metadata file (target positions, dependencies).

**Key methods:**

| Method | Description |
|---|---|
| `getTarget(String)` | Get target by class name |
| `newClass(String)` | Create a new class in this package |
| `compile()` / `compileAll()` | Trigger compilation |
| `invokeMethod(...)` | Run a method via the debugger |
| `loadPackage()` / `savePackage()` | Persist package metadata |

### `PkgMgrFrame.java`

The main window. Contains:
- `PackageEditor` — the class diagram canvas
- `ExecControls` — run/pause/resume buttons
- `ObjectBench` — shows live object instances

---

## 7. Compiler

**Package:** `bluej.compiler`

A thin layer over `javax.tools.JavaCompiler` with async job queuing.

### Class overview

| Class | Role |
|---|---|
| `Compiler` (abstract) | Contract: compile a set of source files |
| `CompilerAPICompiler` | Implements `Compiler` using `javax.tools.JavaCompiler` |
| `CompilerThread` | Runs compile jobs on a background thread |
| `Job` | A single compile request (sources, classpath, options) |
| `JobQueue` | FIFO queue of `Job` objects |
| `Diagnostic` | One compiler message (file, line, col, severity, text) |
| `DiagnosticMessage` | Localised wrapper around `Diagnostic` |
| `CompileObserver` | Interface: `compileStarted()`, `compileError()`, `compileDone()` |

### Compile flow

```
Package.compile()
  → CompilerThread.addJob(new Job(sources, classpath, observer))
  → JobQueue.enqueue(job)
  → (background thread) CompilerAPICompiler.compile(...)
        → javax.tools.JavaCompiler.getTask(...).call()
        → for each diagnostic: observer.compileError(Diagnostic)
        → observer.compileDone(success)
  → (FX thread) FlowEditor.displayDiagnostic(...)
```

---

## 8. Debugger

**Package:** `bluej.debugger`

BlueJ runs user code in a **separate JVM** (the "debug VM") connected over JDWP. This isolates crashes and allows interactive object inspection.

### State machine

```
UNKNOWN → NOTREADY ↔ IDLE ↔ RUNNING ↔ SUSPENDED
                              ↓
                         LAUNCH_FAILED
```

| State | Meaning |
|---|---|
| `NOTREADY` | VM starting up or not yet launched |
| `IDLE` | Ready to execute; no code running |
| `RUNNING` | User code executing |
| `SUSPENDED` | Stopped at breakpoint or step |
| `LAUNCH_FAILED` | Could not start debug VM |

### Key classes

| Class | Role |
|---|---|
| `Debugger` (abstract) | Public interface used by the rest of BlueJ |
| `JdiDebugger` | Implementation via Java Debug Interface |
| `VMReference` | Manages the JDWP socket connection to the remote VM |
| `VMEventHandler` | Background thread; receives and dispatches JDI events |
| `JdiThread` | Proxy for a thread in the remote VM |
| `JdiObject` | Proxy for an object in the remote VM |
| `JdiClass` | Proxy for a class in the remote VM |
| `DebuggerEvent` | Carries event type + thread/exception info to listeners |
| `DebuggerListener` | Interface: `debuggerStateChanged(DebuggerEvent)` |
| `ExecServer` | Runs **inside** the debug VM; receives commands |

### Remote VM startup

```
JdiDebugger.launch()
  → MachineLoaderThread spawns:
       java -cp <classpath> bluej.runtime.ExecServer
       (listens on JDWP socket)
  → VMReference connects via dt_socket transport
  → Sets persistent breakpoints at:
       ExecServer.vmStarted()    ← initial sync point
       ExecServer.vmSuspend()    ← worker thread point
  → State transitions to IDLE
```

### Breakpoint flow

```
User clicks gutter in editor
  → FlowEditor.toggleBreakpoint(line)
  → Debugger.setBreakpoint(className, line, properties)
  → VMReference.setBreakpoint(...)
  → JDI: EventRequestManager.createBreakpointRequest(Location)
  → When hit: VMEventHandler receives BreakpointEvent
  → DebuggerListener notified → editor highlights line
```

### Object bench and method invocation

```
User calls method via dialog
  → Invoker.createShellClass()
       generates temporary Java source that calls target method
  → Compiled to bytecode
  → Loaded into debug VM
  → ExecServer executes the shell class
  → Result marshalled as DebuggerObject
  → Placed on ObjectBench
```

---

## 9. Editor System

**Package:** `bluej.editor`

Two independent editor implementations share a common interface. Both are managed by `FXTabbedEditor`, which wraps them as JavaFX `Tab` objects.

```
FXTabbedEditor  (one per project window)
  └── TabPane
        ├── FlowFXTab    →  FlowEditor     (Java text editor)
        ├── FrameEditorTab → FrameEditor   (Stride block editor)
        └── WebTab                         (documentation browser)
```

---

### 9.1 Interfaces

#### `Editor`

The contract between any editor and the BlueJ core.

| Method | Purpose |
|---|---|
| `setEditorVisible(boolean, boolean)` | Show/hide window |
| `save()` | Write to disk |
| `displayMessage(String, int, int)` | Show a message at a source location |
| `displayDiagnostic(Diagnostic, int, CompileType)` | Show compiler error/warning |
| `setStepMark(int, String, boolean, DebuggerThread)` | Highlight current debugger line |
| `isModified()` | Unsaved changes? |
| `setCompiled(boolean)` | Update compile state |
| `printTo(PrinterJob, ...)` | Print support |
| `getEditorFixesManager()` | Access quick-fixes system |
| `assumeText()` / `assumeFrame()` | Downcast to specific editor type |

#### `TextEditor` (extends `Editor`)

Additional API for text-based editors.

| Method | Purpose |
|---|---|
| `showFile(String, Charset, boolean, String)` | Load a file |
| `insertText(String, boolean)` | Insert at caret |
| `setSelection(SourceLocation, SourceLocation)` | Select a range |
| `getSourceDocument()` | Direct document access |
| `getCaretLocation()` / `setCaretLocation()` | Cursor position |
| `getLineColumnFromOffset(int)` | Offset → (line, col) |
| `getOffsetFromLineColumn(int, int)` | (line, col) → offset |
| `getParsedNode()` | Access parse tree root |

#### `EditorWatcher`

Callbacks the editor fires back into BlueJ core.

| Method | Purpose |
|---|---|
| `modificationEvent(Editor)` | File was edited |
| `saveEvent(Editor)` | File was saved |
| `closeEvent(Editor)` | Editor was closed |
| `breakpointToggleEvent(int, boolean)` | Breakpoint set/cleared |
| `scheduleCompilation(boolean, CompileReason, CompileType)` | Request compile |

---

### 9.2 Flow Editor (Java text)

**Package:** `bluej.editor.flow`

The traditional line-based Java source editor.

#### `FlowEditor`

`extends ScopeColorsBorderPane` (→ `BorderPane`) · `implements TextEditor`

The top-level orchestrator for the text editor window.

**Layout (BorderPane regions):**

| Region | Content |
|---|---|
| `TOP` | Toolbar (`TilePane` of buttons) + interface toggle `ComboBox` |
| `CENTER` | `StackPane(FlowEditorPane, WebView)` — code area or HTML interface view |
| `RIGHT` | `VBox(CodeOutlinePanel, errorListPane)` — outline + error list |
| `BOTTOM` | `FindPanel` + status bar (`Info` + `StatusLabel`) |

**Key fields:**

| Field | Type | Role |
|---|---|---|
| `flowEditorPane` | `FlowEditorPane` | The scrollable code area |
| `document` | `HoleDocument` | Text storage |
| `javaSyntaxView` | `JavaSyntaxView` | Syntax highlight & parsing |
| `actions` | `FlowActions` | Keyboard/menu action registry |
| `finder` | `FindPanel` | Find/replace UI |
| `errorManager` | `FlowErrorManager` | Error underlines |
| `codeOutlinePanel` | `CodeOutlinePanel` | Structure sidebar |
| `saveState` | `StatusLabel` | Saved/modified/error indicator |
| `undoManager` | `UndoManager` | Undo/redo history |
| `editorFixesMgr` | `EditorFixesManager` | Quick-fix suggestions |
| `htmlPane` | `WebView` | Javadoc interface view |

#### `FlowEditorPane`

`extends BaseEditorPane` · `implements JavaSyntaxView.Display`

The scrollable code surface. Renders only the visible lines (virtual scrolling).

**Responsibilities:**
- Accepts keyboard and mouse input
- Manages caret position and text selection
- Delegates line rendering to `BaseEditorPane` / `LineDisplay`
- Reports changes to `JavaSyntaxView` for re-parsing
- Shows bracket matching and find-result highlights

**Key methods:**

| Method | Purpose |
|---|---|
| `positionCaret(int offset)` | Move caret to document offset; scroll to show it |
| `getCaretPosition()` | Current offset |
| `getSelection[Start\|End]()` | Selection range |
| `undo()` / `redo()` | Undo/redo a change |
| `setBracketMatches(List)` | Highlight matching brackets |
| `setLineStyler(LineStyler)` | Plug in syntax highlight callback |
| `scrollTo(int line)` | Scroll to a line number |

#### `JavaSyntaxView`

`implements ReparseableDocument`, `LineDisplayListener`

Continuous incremental parser and syntax highlighter.

**Responsibilities:**
- Holds `ParsedCUNode rootNode` — the live parse tree
- Re-parses on every document change via `FlowReparseRunner`
- Produces per-line styled segments (colors/fonts) for rendering
- Manages scope visualization (colored block backgrounds)
- Fires `onStructureChanged` when a parse cycle completes

**Incremental re-parse loop:**

```
Document edit arrives
  → scheduleReparseRunner()
  → FlowReparseRunner runs in slices (≤15ms each)
        processes pending reparse records
        re-schedules itself if queue not empty
  → when queue empty:
        applyPendingScopeBackgrounds()
        display.repaint()
        notifyStructureChanged()   ← triggers CodeOutlinePanel refresh
```

**Key methods:**

| Method | Purpose |
|---|---|
| `getRootNode()` | Returns the current `ParsedCUNode` |
| `setOnStructureChanged(Runnable)` | Register post-parse callback |
| `getScopeBackgrounds()` | Scope color backgrounds by line |
| `enableParser(boolean)` | Toggle parsing (disabled for read-only/large files) |
| `getEntityResolver()` | Type resolver for code completion |

#### `HoleDocument` / `Document`

Gap-buffer text storage. Positions are integer offsets; `TrackedPosition` objects follow text as it moves.

**Key `Document` methods:**

| Method | Purpose |
|---|---|
| `replaceText(start, end, text)` | Core edit primitive |
| `getLineFromPosition(int)` | Offset → line number |
| `getLineStart(int)` / `getLineEnd(int)` | Line boundary offsets |
| `trackPosition(int, Bias)` | Create a position that survives edits |
| `addListener(boolean, DocumentListener)` | Subscribe to changes |

#### `FlowActions`

Registry of ~80 named editor actions (cut, copy, paste, undo, find, compile, etc.) with configurable key bindings. Loaded from `moe.defs` / `moe.properties`.

#### `FindPanel`

`extends GridPane`

Find and replace bar that slides in at the bottom of the editor. Highlights all matches and navigates between them. Supports case-sensitive search and plain/regex modes.

#### `StatusLabel`

Three states: `SAVED`, `CHANGED`, `READONLY`. Also shows error count. Clicking it when there are errors triggers `compileOrShowNextError()`.

#### `FlowErrorManager`

Keeps the current list of `ErrorDetails`. Produces error underline highlights in the text and coordinates with `EditorFixesManager` to show quick-fix lightbulbs.

---

### 9.3 Text Rendering Stack

**Package:** `bluej.editor.base`

Pure rendering — no knowledge of Java syntax or file I/O.

```
BaseEditorPane (Region)
  ├── LineContainer (Region)           — parent of all visible rows
  │    └── MarginAndTextLine (Region)  — one per visible line
  │         ├── Region (margin bg)    — background fill for gutter
  │         ├── Line (divider)        — separator between gutter and text
  │         ├── Label (line number)
  │         ├── Node (breakpoint icon)
  │         ├── Node (step mark icon)
  │         └── TextLine (TextFlow)   — the actual code text
  │              ├── BackgroundItem*  — scope color rectangles
  │              ├── Path (selection) — selection highlight
  │              ├── Path (bracket)   — bracket match highlight
  │              ├── Path (find)      — search result highlight
  │              ├── Text*            — styled character runs
  │              └── Path (errors)    — red squiggle underline
  ├── Path (caret)                     — blinking text cursor
  ├── ScrollBar (vertical)
  └── ScrollBar (horizontal)
```

#### `BaseEditorPane`

`extends Region`

Manages the three top-level children (LineContainer, two scroll bars), custom `layoutChildren()`, scroll event batching, and caret animation. Subclassed by `FlowEditorPane`.

#### `LineDisplay`

Not a JavaFX node — a manager. Keeps `Map<Integer, MarginAndTextLine> visibleLines`. On each scroll or resize event, calculates which line indices should be visible, creates/recycles `MarginAndTextLine` nodes, and hands the resulting list to `LineContainer`.

#### `MarginAndTextLine`

`extends Region`

One physical row. `layoutChildren()` positions the margin group on the left (width `MARGIN_BACKGROUND_WIDTH`) and the `TextLine` on the right starting at `TEXT_LEFT_EDGE` (32 px with margin, 2 px without).

**Margin pseudo-classes:** `bj-margin-uncompiled`, `bj-margin-error` (trigger CSS gutter background colors).

#### `TextLine`

`extends TextFlow`

One line of text. `setText(List<StyledSegment>)` replaces the child `Text` nodes and `BackgroundItem` nodes. Unmanaged `Path` shapes for selection, bracket, find, and error highlights are repositioned in `layoutChildren()` after calling `super.layoutChildren()`.

**`StyledSegment`** — a text string plus a list of CSS style classes. Multiple segments make up one `TextLine`.

---

### 9.4 Stride Editor (block-based)

**Package:** `bluej.editor.stride`

The Stride editor represents code as visual **frames** (blocks) instead of text.

#### `FXTabbedEditor`

Top-level window container (one per project). Holds a `TabPane`; orchestrates inter-tab drag-and-drop, the `FrameCatalogue`, error overview bar, and menu bar.

#### `FrameEditor`

`implements Editor`

The logical Stride editor. Loads/saves `.stride` files, generates equivalent Java source for compilation, handles error display and debugger integration. Can exist without the visual tab being open.

#### `FrameEditorTab`

`extends FXTab` · `implements InteractionManager`

The visual editor inside a tab. Manages the frame canvas, code completion popup, frame selection, and overlays. Bridges user gestures to `FrameEditor` logic.

#### `FrameCatalogue`

Right-side panel showing available frame types (if/while/for/method call etc.) and keyboard shortcuts. Updates dynamically based on what frames are valid at the current cursor position.

#### `FlowFXTab` / `WebTab`

`FlowFXTab` wraps a `FlowEditor` as a tab. `WebTab` shows a `WebView` for Javadoc.

---

### 9.5 Quick Fixes

**Package:** `bluej.editor.fixes`

| Class | Role |
|---|---|
| `EditorFixesManager` | Owns the suggestion list; shows/hides fix UI |
| `FixSuggestion` | One suggested fix (description + action) |
| `Correction` | An auto-correction action (e.g., add import, fix typo) |
| `SuggestionList` | Popup `ListView` of fix options |
| `SuggestionCell` | One row in the suggestion list |
| `ProjectImportInformation` | Tracks importable classes for "add import" fixes |

---

## 10. Parser

**Package:** `bluej.parser`

A custom incremental Java parser. Rather than building a full AST, it builds a lightweight **structural tree** containing only the nodes needed for syntax highlighting, code completion, and the outline panel.

### Sub-packages

| Package | Content |
|---|---|
| `bluej.parser` | Top-level: `JavaParser`, `EditorParser`, `TextAnalyzer`, `InfoParser` |
| `bluej.parser.nodes` | Parse tree node hierarchy |
| `bluej.parser.entity` | Type resolution entities |
| `bluej.parser.lexer` | Tokenizer (`JavaLexer`, `JavaTokenFilter`) |
| `bluej.parser.symtab` | Symbol table (`ClassInfo`, `Selection`) |
| `bluej.parser.context` | Context preservation during parse |

### Parse tree node hierarchy

```
ParsedNode  (abstract, RBTreeNode<ParsedNode>)
  ├── IncrementalParsingNode    — nodes that support partial re-parse
  │    ├── ParsedCUNode         — compilation unit (one .java file)
  │    │    └── ParsedTypeNode  — class / interface / enum / annotation
  │    └── JavaParentNode       — general parent node
  │         ├── MethodNode      — method or constructor
  │         └── FieldNode       — field or variable declaration
  └── (expression, iteration, selection nodes)
```

**`ParsedNode` node types** (`NODETYPE_*` constants):

| Constant | Value | Represents |
|---|---|---|
| `NODETYPE_TYPEDEF` | 1 | Class, interface, enum, annotation |
| `NODETYPE_METHODDEF` | 2 | Method or constructor |
| `NODETYPE_ITERATION` | 3 | `for`, `while`, `do-while` |
| `NODETYPE_SELECTION` | 4 | `if`, `switch`, `try-catch` |
| `NODETYPE_FIELD` | 5 | Field or local variable |
| `NODETYPE_EXPRESSION` | 6 | Expression |
| `NODETYPE_COMMENT` | 7 | Comment block |

### `NodeTree<T>` — the spatial index

A **red-black balanced binary search tree** where nodes are indexed by their character offset in the document. Supports O(log n) lookups by position:

```
findNode(pos, startpos)          → node overlapping position
findNodeAtOrBefore(pos)          → rightmost node ≤ position
insertNode(child, position, size)
resize(newSize)                  → cascades up to parent
iterator(offset)                 → in-order traversal
```

`NodeAndPosition<T>` pairs a node with its absolute position and size.

### Incremental re-parsing

`IncrementalParsingNode` maintains an array of **state markers** — known-good parse boundaries. When an edit occurs:

1. The affected position is found in the tree (O(log n)).
2. `reparseNode()` is called on the smallest enclosing node.
3. If a child is a **delimited node** (e.g., a complete method body), parsing skips past it — no need to re-parse unchanged code.
4. Size changes propagate up via `ParsedNode.resize()`.
5. `NodeStructureListener` notified of additions/removals.

### `JavaParser` and callbacks

`JavaParser` (extends `JavaParserCallbacks`) implements the grammar. It is **callback-driven**: as it recognises constructs it calls protected methods like `beginElement()`, `gotModifier()`, `endMethodDecl()`. Subclasses (e.g., `EditorParser`) override these callbacks to build the parse tree.

### `NodeAndPosition<T>` — position-aware node wrapper

Every child returned by iteration or lookup is wrapped in `NodeAndPosition<ParsedNode>`, which pairs the node with its position in the document.

| Method | Return | Description |
|---|---|---|
| `getNode()` | `T` | The wrapped node |
| `getPosition()` | `int` | Absolute character offset of the node's start |
| `getSize()` | `int` | Length in characters |
| `getEnd()` | `int` | `getPosition() + getSize()` — exclusive end offset |
| `nextSibling()` | `NodeAndPosition<T>` | Next sibling in the tree (null if none) |
| `prevSibling()` | `NodeAndPosition<T>` | Previous sibling in the tree (null if none) |

### `ParsedNode` — base class API

`extends RBTreeNode<ParsedNode>` (abstract)

| Method | Return | Description |
|---|---|---|
| `getNodeType()` | `int` | One of the `NODETYPE_*` constants; returns `NODETYPE_NONE` in base class |
| `getName()` | `String` | Symbol name (class/method/field name); returns `null` in base class |
| `getChildren(int offset)` | `Iterator<NodeAndPosition<ParsedNode>>` | In-order iterator over direct children starting at `offset` |
| `getParentNode()` | `ParsedNode` | Immediate parent; `null` at the root |
| `getOffsetFromParent()` | `int` | Character offset of this node relative to its parent |
| `isComplete()` | `boolean` | Whether the node's closing token has been parsed |
| `isContainer()` | `boolean` | Whether this node is a scope container for highlighting |
| `findNodeAt(int pos, int startpos)` | `NodeAndPosition<ParsedNode>` | Child overlapping `pos`; `startpos` is this node's absolute position |
| `findNodeAtOrAfter(int pos, int startpos)` | `NodeAndPosition<ParsedNode>` | Leftmost child at or after `pos` |

### `ParsedCUNode` — compilation unit root

`extends IncrementalParsingNode` — one instance per open `.java` file; returned by `JavaSyntaxView.getRootNode()`.

| Method | Return | Description |
|---|---|---|
| `getImports()` | `ImportsCollection` | All import statements in the file |
| `getParentResolver()` | `EntityResolver` | Resolver for types visible from this file's package |
| `getSize()` | `int` | Total document length in characters |
| `getChildren(0)` | `Iterator<NodeAndPosition<ParsedNode>>` | Top-level nodes (type definitions, package statement, etc.) |

### `ParsedTypeNode` — class / interface / enum / annotation

`extends IncrementalParsingNode` — one per class, interface, enum, or annotation declaration.

Cast from a `ParsedNode` when `getNodeType() == NODETYPE_TYPEDEF`.

| Method | Return | Description |
|---|---|---|
| `getName()` | `String` | Simple class name (e.g. `"MyClass"`) |
| `getPrefix()` | `String` | Package prefix including trailing `.` (e.g. `"com.example."`) |
| `getTypeKind()` | `int` | One of `JavaParser.TYPEDEF_CLASS`, `TYPEDEF_INTERFACE`, `TYPEDEF_ENUM`, `TYPEDEF_ANNOTATION` |
| `getModifiers()` | `int` | `java.lang.reflect.Modifier` bitmask (e.g. `Modifier.PUBLIC \| Modifier.ABSTRACT`) |
| `getExtendedTypes()` | `List<JavaEntity>` | Types listed after `extends` |
| `getImplementedTypes()` | `List<JavaEntity>` | Types listed after `implements` |
| `getTypeParams()` | `List<TparEntity>` | Generic type parameters (e.g. `<T, E extends Comparable<E>>`) |
| `getContainingClass()` | `ParsedTypeNode` | Enclosing class for nested types; `null` for top-level |
| `getInner()` | `TypeInnerNode` | The `{…}` body node; `null` if incomplete |
| `getChildren(pos)` | `Iterator<NodeAndPosition<ParsedNode>>` | Children: `MethodNode`, `FieldNode`, nested `ParsedTypeNode` |

**`JavaParser.TYPEDEF_*` constants:**

| Constant | Value | Meaning |
|---|---|---|
| `TYPEDEF_CLASS` | 1 | `class` declaration |
| `TYPEDEF_INTERFACE` | 2 | `interface` declaration |
| `TYPEDEF_ENUM` | 3 | `enum` declaration |
| `TYPEDEF_ANNOTATION` | 4 | `@interface` declaration |

### `MethodNode` — method or constructor

`extends JavaParentNode` — one per method or constructor declaration.

Cast from a `ParsedNode` when `getNodeType() == NODETYPE_METHODDEF`.

| Method | Return | Description |
|---|---|---|
| `getName()` | `String` | Method name |
| `getReturnType()` | `JavaEntity` | Return type entity; **`null` for constructors** |
| `getParamNames()` | `List<String>` | Parameter names in declaration order |
| `getParamTypes()` | `List<JavaEntity>` | Parameter type entities in declaration order; parallel to `getParamNames()` |
| `getModifiers()` | `int` | `java.lang.reflect.Modifier` bitmask |
| `isVarArgs()` | `boolean` | Whether the last parameter is variadic (`...`) |
| `getJavadoc()` | `String` | Attached Javadoc comment text; `null` if none |
| `getTypeParams()` | `List<GenTypeDeclTpar>` | Generic type parameters on the method itself; `null` if none |

### `FieldNode` — field or local variable declaration

`extends JavaParentNode` — one per declared field/variable. Multi-variable declarations (`int x, y`) produce multiple `FieldNode` instances that share a "first node" reference.

Cast from a `ParsedNode` when `getNodeType() == NODETYPE_FIELD`.

| Method | Return | Description |
|---|---|---|
| `getName()` | `String` | Variable name |
| `getFieldType()` | `JavaEntity` | Type entity (requires resolution for full generic form) |
| `getFieldTypeAsPlainString()` | `String` | Simple type name string — handles `var`, compound declarations, and array brackets. The easiest option for display. |
| `getModifiers()` | `int` | `java.lang.reflect.Modifier` bitmask; delegates to first node if this is a subsequent declarator |
| `isFirstFieldNode()` | `boolean` | `true` if this is the first declared name in a compound declaration (only first node carries the type) |

### `JavaEntity` — type/name resolution entity

Abstract base for all named things the parser can resolve. Used as the carrier for types not yet resolved against the classpath.

| Method | Return | Description |
|---|---|---|
| `getName()` | `String` | Unqualified source-text name (e.g. `"List"`, `"void"`, `"int"`) |
| `resolveAsType()` | `TypeEntity` | Attempt to resolve as a type; `null` on failure |
| `resolveAsValue()` | `ValueEntity` | Attempt to resolve as a value; `null` on failure |
| `resolveAsPackageOrClass()` | `PackageOrClass` | Attempt to resolve as package or class; `null` on failure |
| `getType()` | `JavaType` | Resolved `JavaType` if already a `TypeEntity` or `ValueEntity` |
| `getSubentity(name, source)` | `JavaEntity` | Member access: `entity.getSubentity("size", null)` for `list.size` |

> **Note:** `getName()` returns the unresolved source-level name. To get the fully qualified name you must call `resolveAsType()?.getType()?.toString()`, which requires a working classpath resolver. For display purposes (outline panel, tooltips), `getName()` is sufficient.

### Downcasting from `ParsedNode`

The parse tree stores everything as `ParsedNode`. Access the richer API by downcasting using `instanceof` patterns:

```java
// Inside walkNode() or any traversal:
ParsedNode child = nap.getNode();

if (child instanceof MethodNode mn) {
    String name     = mn.getName();
    String retType  = mn.getReturnType() != null ? mn.getReturnType().getName() : null; // null = constructor
    List<String>      paramNames = mn.getParamNames();
    List<JavaEntity>  paramTypes = mn.getParamTypes();
    // Build a signature: "doThing(String, int) : void"
    String sig = name + "(" +
        IntStream.range(0, paramNames.size())
                 .mapToObj(i -> paramTypes.get(i).getName() + " " + paramNames.get(i))
                 .collect(Collectors.joining(", ")) + ")" +
        (retType != null ? " : " + retType : "");
    boolean isStatic = Modifier.isStatic(mn.getModifiers());
    String javadoc   = mn.getJavadoc();  // may be null
}

if (child instanceof FieldNode fn) {
    String name      = fn.getName();
    String typeName  = fn.getFieldTypeAsPlainString(); // "String[]", "int", "var"
    boolean isStatic = Modifier.isStatic(fn.getModifiers());
    boolean isFinal  = Modifier.isFinal(fn.getModifiers());
}

if (child instanceof ParsedTypeNode tn) {
    String name    = tn.getName();
    int kind       = tn.getTypeKind(); // JavaParser.TYPEDEF_CLASS / _INTERFACE / _ENUM / _ANNOTATION
    boolean isAbstract = Modifier.isAbstract(tn.getModifiers());
    List<JavaEntity> superTypes   = tn.getExtendedTypes();   // "extends X"
    List<JavaEntity> ifaces       = tn.getImplementedTypes(); // "implements A, B"
    // Recurse into the class body:
    walkNode(tn, nap.getPosition(), parentItem);
}
```

Imports required:
```java
import bluej.parser.nodes.FieldNode;
import bluej.parser.nodes.MethodNode;
import bluej.parser.nodes.ParsedTypeNode;
import bluej.parser.JavaParser;     // for TYPEDEF_* constants
import java.lang.reflect.Modifier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
```

### Downcasting pitfalls

#### Cast placement — apply the cast to the node, not to the method call

```java
// WRONG: casts the String returned by getPrefix(), not the node
result = (ParsedTypeNode) node.getPrefix();

// CORRECT: cast the node first, then call the method
result = ((ParsedTypeNode) node).getPrefix();
```

Java's cast operator binds tighter than method calls only within the cast expression itself. When the cast and the method call are at the same level, the cast applies to the immediately following expression — which is `node`, not `node.getPrefix()`. Adding the extra parentheses around the cast + node makes the order unambiguous.

The modern alternative avoids this entirely by using `instanceof` pattern matching, which binds and casts in one expression:

```java
// Best: no separate cast needed
if (child instanceof ParsedTypeNode tn) {
    String prefix = tn.getPrefix();
}
```

#### `ParsedTypeNode.getPrefix()` is the package path, not a display label

`getPrefix()` returns the package qualification prefix **including the trailing dot**, e.g. `"com.example."` for a class in `com.example`. It is not a useful value to append to the class name in a UI. For the simple class name, use `getName()`. To show the kind of type (class vs interface vs enum), use `getTypeKind()` and map it to a label:

```java
String kindLabel = switch (tn.getTypeKind()) {
    case JavaParser.TYPEDEF_CLASS      -> "class";
    case JavaParser.TYPEDEF_INTERFACE  -> "interface";
    case JavaParser.TYPEDEF_ENUM       -> "enum";
    case JavaParser.TYPEDEF_ANNOTATION -> "@interface";
    default -> "";
};
```

#### `getName()` can return null — guard before concatenation

For wrapper nodes (`PkgStmtNode`, `ImportNode`, `TypeInnerNode`) `getName()` returns `null`. If you concatenate the result before checking:

```java
// WRONG: produces "null : int" when getName() returns null
String label = child.getName() + " : " + typeString;
if (label != null) { ... }  // always true — null + String = "null..."

// CORRECT: check getName() before using it
String rawName = child.getName();
if (rawName != null && !rawName.isEmpty()) {
    String label = rawName + " : " + typeString;
}
```

#### `switch` on `int` without `default` leaves variables uninitialized

A `switch` expression or statement on `getNodeType()` does not cover every possible int value. If none of the cases match, any variable assigned only inside the switch is uninitialized — a compile error in Java:

```java
// WRONG: result is uninitialized if type is not one of the three cases
String result;
switch (type) {
    case ParsedNode.NODETYPE_TYPEDEF   -> { result = "..."; }
    case ParsedNode.NODETYPE_METHODDEF -> { result = "..."; }
    case ParsedNode.NODETYPE_FIELD     -> { result = "..."; }
}
return result;  // compile error: variable result might not have been initialized

// CORRECT: provide a default
String result = "";
switch (type) {
    case ParsedNode.NODETYPE_TYPEDEF   -> result = "...";
    case ParsedNode.NODETYPE_METHODDEF -> result = "...";
    case ParsedNode.NODETYPE_FIELD     -> result = "...";
}
return result;
```

### Parent–child relationships in the parse tree

```
ParsedCUNode  (file root, offset 0..fileLength)
  │
  ├── PkgStmtNode        (package declaration)
  ├── ImportNode (*)     (import statements)
  └── ParsedTypeNode (*) (top-level class/interface/enum)
        │
        └── TypeInnerNode  (the { ... } body)
              │
              ├── FieldNode (*)      (field declarations)
              ├── MethodNode (*)     (methods and constructors)
              └── ParsedTypeNode (*) (nested/inner classes)
                    └── TypeInnerNode
                          └── ...
```

Non-symbol wrapper nodes (`PkgStmtNode`, `ImportNode`, and `TypeInnerNode`) have `getNodeType() == NODETYPE_NONE` and `getName() == null`. When walking for display purposes, recurse into them without creating a visible tree item (the pass-through branch in `walkNode`).

`MethodNode` children are not walked — local variable declarations inside method bodies (`NODETYPE_FIELD`) are not useful in an outline and would produce noise.

### Useful traversal helpers

| Method | Where | Purpose |
|---|---|---|
| `ParsedNode.getChildren(offset)` | `ParsedNode` | In-order iterator from `offset` |
| `ParsedNode.findNodeAt(pos, startpos)` | `ParsedNode` | Single child overlapping `pos` |
| `ParsedNode.findNodeAtOrAfter(pos, startpos)` | `ParsedNode` | First child at or after `pos` |
| `ParsedCUNode.getImports()` | `ParsedCUNode` | All imports |
| `MethodNode.getParamNames()` / `getParamTypes()` | `MethodNode` | Parallel parameter lists |
| `FieldNode.getFieldTypeAsPlainString()` | `FieldNode` | Type as source string |
| `ParsedTypeNode.getExtendedTypes()` | `ParsedTypeNode` | `extends` clause entities |
| `ParsedTypeNode.getImplementedTypes()` | `ParsedTypeNode` | `implements` clause entities |

---

## 11. Class & Type System

**Package:** `bluej.parser.entity`, `bluej.debugger.gentype`, `bluej.classmgr`

### Entity system (`bluej.parser.entity`)

Used during parsing for name resolution and code completion.

| Class | Represents |
|---|---|
| `JavaEntity` | Base for all resolvable names |
| `TypeEntity` | A resolved type |
| `ValueEntity` | A resolved value (field, local variable) |
| `PackageOrClass` | Ambiguous name (package or class) |
| `TparEntity` | A type parameter |
| `ImportedEntity` | A name brought in by an import statement |
| `UnresolvedEntity` / `ErrorEntity` | Failed resolution |
| `EntityResolver` | Interface: `resolvePackageOrClass(name, typeargs)` |

### Generic type system (`bluej.debugger.gentype`)

Used at runtime for inspection and reflection.

| Class | Represents |
|---|---|
| `JavaType` | Any Java type |
| `GenTypeClass` | A class type with optional type arguments |
| `GenTypeTpar` | A type parameter reference |
| `GenTypeWildcard` | `?`, `? extends T`, `? super T` |
| `GenTypeArray` | An array type |
| `Reflective` | Bridge to runtime reflection |
| `ParsedReflective` | Wraps `ParsedTypeNode` as a `Reflective` |

### Class loader (`bluej.classmgr`)

| Class | Role |
|---|---|
| `BPClassLoader` | `URLClassLoader` subclass — one per project, isolates user classes |
| `ClassPathEntry` | One entry on the project classpath |

A new `BPClassLoader` is created after each successful compilation so the JVM loads fresh `.class` files.

---

## 12. Extensions API

**Package:** `bluej.extensions2`

Third-party extensions subclass `Extension` and receive a `BlueJ` handle on startup.

### Core classes

| Class | Role |
|---|---|
| `Extension` (abstract) | Base class all extensions must extend |
| `BlueJ` | Entry point — query projects, register listeners, show dialogs |
| `BProject` | API view of a project |
| `BPackage` | API view of a package |
| `BClass` | API view of a class |
| `BObject` | API view of a runtime object |
| `BMethod` / `BConstructor` | API view of callable members |
| `ExtensionBridge` | Internal adapter between API and core objects |

### Extension lifecycle

```
BlueJ discovers JAR in extensions/ directory
  → loads Extension subclass
  → calls isCompatible() — reject if API version mismatch
  → calls startup(BlueJ bluej)
  → extension registers event listeners, adds menu items, etc.

On BlueJ exit:
  → calls terminate() on each extension
```

### API version

Major: `3`, Minor: `4`. Extensions call `getExtensionsAPIVersionMajor()` / `Minor()` to verify compatibility.

---

## 13. Version Control

**Package:** `bluej.groupwork`

Provides team collaboration. Supports Git, SVN, and CVS through a common `Repository` interface.

### `Repository` interface

| Method | Purpose |
|---|---|
| `checkout(File)` | Clone remote repo to local path |
| `commitAll(newFiles, deletedFiles, ...)` | Commit staged changes |
| `pushChanges()` | Push to remote (distributed VCS) |
| `getStatus(StatusListener, ...)` | Async status query |
| `getLogHistory(LogHistoryListener)` | Async commit history |
| `shareProject()` | Publish project to a new remote |
| `versionsDirectories()` | `true` for SVN (versions `.svn/`), `false` for CVS |

Commands are wrapped in `TeamworkCommand` objects (async, cancellable). Results arrive via listener callbacks (`StatusListener`, `LogHistoryListener`).

---

## 14. Preferences

**Package:** `bluej.prefmgr`

**`PrefMgr`** is a singleton managing all user preferences as reactive JavaFX properties.

### Key preference areas

| Category | Example keys |
|---|---|
| Editor | `HIGHLIGHTING`, `AUTO_INDENT`, `LINENUMBERS`, `MATCH_BRACKETS` |
| Display | `SCOPE_HIGHLIGHTING_STRENGTH`, `NAVIVIEW_EXPANDED` |
| Testing | `SHOW_TEST_TOOLS`, `SHOW_TERMINAL_SCOPES` |
| Team | `SHOW_TEAM_TOOLS` |
| Font | `editorFontSize`, `editorStandardFont` (Source Code Pro default) |
| Print | `PRINT_FONT_SIZE`, `PRINT_LINE_NUMBERS` |

### Access pattern

```java
// Read
boolean show = PrefMgr.getFlag(PrefMgr.SHOW_TEST_TOOLS);
int size = PrefMgr.getEditorFontSize();

// Bind (reactive)
BooleanProperty prop = PrefMgr.getFlagProperty(PrefMgr.HIGHLIGHTING);
someNode.visibleProperty().bind(prop);

// Write
PrefMgr.setFlag(PrefMgr.AUTO_INDENT, true);
```

---

## 15. Testing Infrastructure

**Package:** `bluej.testmgr`

BlueJ can **record** method calls on object-bench instances and replay them as JUnit tests.

| Class | Role |
|---|---|
| `TestDisplayFrame` | Shows pass/fail results in a JUnit-style panel |
| `InvokerRecord` | Base class for recorded invocations |
| `TestRunnerThread` | Executes the recorded tests |

**Gradle test setup:**
- `monocle` headless platform for JavaFX UI tests
- Parallel forks: `2 × availableProcessors()`
- TestFX for automated UI interaction

---

## 16. Threading Model

BlueJ uses a compile-time **thread checker** (`threadchecker.TCPlugin`) that validates `@OnThread` annotations at build time. A violation is a **compile error**.

| Tag | Meaning | Where used |
|---|---|---|
| `Tag.FXPlatform` | JavaFX Application Thread — safe to touch UI | All UI classes |
| `Tag.FX` | FX thread (slightly broader than FXPlatform) | JavaFX library callbacks |
| `Tag.Any` | Thread-safe; callable from anywhere | Utility methods, `toString()` on shared objects |
| `Tag.Worker` | Background worker thread | Compilation, I/O |
| `Tag.Swing` | Swing EDT (deprecated) | Legacy components |

### Rules

- UI components (`Node`, `Stage`, etc.) must only be modified on `FXPlatform`.
- Listeners provided by JavaFX libraries (e.g., `ChangeListener`) run on `Tag.FX`. Calling a `FXPlatform` method from them requires an anonymous inner class annotated `@OnThread(value = Tag.FXPlatform, ignoreParent = true)`.
- `ignoreParent = true` tells the checker to use the locally declared tag rather than inheriting from the enclosing class.
- Cross-thread calls use `JavaFXUtil.runAfterCurrent(Runnable)` or `Platform.runLater(Runnable)`.

---

## 17. Key Design Patterns

| Pattern | Where used |
|---|---|
| **Singleton** | `Config`, `PrefMgr`, `ExtensionsManager`, `Project` (per directory) |
| **Observer / Listener** | `DebuggerListener`, `CompileObserver`, `EditorWatcher`, `DocumentListener`, `BlueJEventListener` |
| **Command** | `TeamworkCommand`, `Job` (compile), `InvokerRecord` (method call recording) |
| **Factory** | `DebuggerFactory`, `PackageFileFactory` |
| **Strategy** | `Compiler` implementations, `Repository` implementations, `LineStyler` |
| **Template Method** | `Compiler` (abstract `compile()`), `Debugger` (abstract launch/close), `IncrementalParsingNode` (abstract `doPartialParse()`) |
| **Proxy** | `JdiObject`, `JdiClass`, `JdiThread` — wrap remote VM objects |
| **Adapter** | `ExtensionBridge` — adapts internal classes to the extensions API |
| **Reactive Properties** | `PrefMgr` exposes `BooleanProperty`/`StringProperty` for data binding |

---

## 18. Data Flow Diagrams

### Editing → syntax highlight → outline update

```
Keystroke in FlowEditorPane
  │
  ▼
HoleDocument.replaceText(start, end, newText)
  │
  ├─► DocumentListener.textChanged()  →  JavaSyntaxView.fireInsertUpdate()
  │                                          scheduleReparseRunner()
  │
  └─► FlowEditorPane re-renders affected lines (immediate)

(later, on FX thread)
FlowReparseRunner.run()  [≤15ms slices, re-queues until done]
  │
  ├─► processReparseQueue()  →  ParsedNode.reparseNode()
  │                                  incrementally updates parse tree
  │
  └─► (when queue empty)
        applyPendingScopeBackgrounds()   → scope colour backgrounds
        display.repaint()               → line style refresh
        notifyStructureChanged()
              │
              ▼
        FlowEditor lambda (setOnStructureChanged)
              │
              ▼
        JavaFXUtil.runAfterCurrent(...)
              │
              ▼
        CodeOutlinePanel.refresh(javaSyntaxView.getRootNode())
              │
              ▼
        walkNode() traverses ParsedCUNode tree
              │
              ▼
        TreeView rebuilt with new TreeItems
```

### Compile → error display

```
User presses Compile button / auto-compile triggers
  │
  ▼
FlowEditor.compileOrShowNextError()
  → EditorWatcher.scheduleCompilation(...)
  → Package.compile()
  → CompilerThread enqueues Job
  → (background) CompilerAPICompiler.compile(sources)
        foreach diagnostic:
          CompileObserver.compileError(Diagnostic)
            → FlowEditor.displayDiagnostic(d)
              → FlowErrorManager.addErrorHighlight(line, col, msg)
                  creates Path underline in TextLine
                  creates ErrorDetails for errorListPane
              → StatusLabel updates error count
```

### Breakpoint hit → editor highlight

```
User code hits breakpoint in debug VM
  │
  ▼
JDI sends BreakpointEvent over JDWP socket
  │
  ▼
VMEventHandler.handleEvent(BreakpointEvent)
  │
  ▼
JdiDebugger notifies DebuggerListener.debuggerStateChanged(SUSPENDED)
  │
  ▼
ExecControls updates run/pause buttons
  │
  ▼
FlowEditor.setStepMark(lineIndex, ...)
  │
  ▼
MarginAndTextLine shows step mark icon in gutter
TextLine shows step mark background
```

### Outline click → caret navigation

```
User clicks TreeItem in CodeOutlinePanel
  │
  ▼
ChangeListener.changed()  @OnThread(FXPlatform, ignoreParent=true)
  │
  ▼
navCallback.navigateTo(item.position())
  │
  ▼
flowEditorPane.positionCaret(offset)
  → caret moves to character offset
  → editor scrolls to show caret
flowEditorPane.requestFocus()
  → keyboard focus returns to editor
```

---

## 19. Code Outline Sidebar (Custom Feature)

The outline panel is a sidebar added to the right of the Flow Editor. It displays the structural symbols of the current Java file as a tree and navigates to them on click.

### Files changed

| File | Change |
|---|---|
| `bluej/editor/flow/CodeOutlinePanel.java` | **New file** — entire panel implementation |
| `bluej/editor/flow/JavaSyntaxView.java` | Added `getRootNode()`, `setOnStructureChanged()`, `notifyStructureChanged()` |
| `bluej/editor/flow/FlowEditor.java` | Added panel field, layout wiring, navigation callback |
| `bluej/lib/stylesheets/flow.css` | Panel CSS |
| `build.gradle` (root) | Fixed `allprojects {}` Java release target |

### `CodeOutlinePanel` class

`extends VBox` · `@OnThread(Tag.FXPlatform)`

| Member | Purpose |
|---|---|
| `treeView` | `TreeView<OutlineItem>` — the visible tree |
| `navCallback` | Called with document offset when a row is clicked |
| `refresh(ParsedNode)` | Rebuild tree from parse root; called after every re-parse |
| `setNavigationCallback(cb)` | Wire in the scroll-to action |
| `walkNode(node, pos, parent)` | Recursive tree builder |
| `iconForType(int)` | Returns `C`/`M`/`F` label with colour for class/method/field |
| `OutlineItem` record | `(name, nodeType, position)` — data for one tree row |
| `NavigationCallback` | `@FunctionalInterface` — `void navigateTo(int offset)` |

### `OutlineItem` thread annotation

`toString()` overrides `Object.toString()` which has no thread tag, but the outer class is tagged `FXPlatform`. The checker rejects the mismatch. Fix: `@OnThread(Tag.Any)` on `toString()`.

### Selection listener thread issue

JavaFX `ChangeListener` is tagged `Tag.FX`. Calling a `FXPlatform` method from inside it is rejected by the thread checker. Fix: use an anonymous inner class with `@OnThread(value = Tag.FXPlatform, ignoreParent = true)` on the `changed()` method instead of a lambda.

### Build fix

The root `build.gradle` had `tasks.withType(JavaCompile)` outside any block — this only applies to the root project. Sub-projects compiled with whatever JDK was on `PATH` (Java 26 in this environment), while `bluej` declared it needed Java 21-compatible dependencies. Gradle's variant resolution refused the mismatch. Fix: wrap in `allprojects { }` so every sub-project targets Java 21.

### CSS classes

| Class | Applied to | Effect |
|---|---|---|
| `.code-outline-panel` | The VBox | Light grey background, left border |
| `.code-outline-header` | "Outline" label | Bold, bottom border separating it from tree |
| `.code-outline-tree .tree-cell` | Each row | Smaller font |
| `.outline-icon` | C/M/F label | Fixed width, centred, bold |
| `.outline-icon-class` | C label | Blue `#5b8dd9` |
| `.outline-icon-method` | M label | Brown `#a67c52` |
| `.outline-icon-field` | F label | Green `#6aaa5e` |
