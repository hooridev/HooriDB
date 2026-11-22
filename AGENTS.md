# HooriDB

A database manager for the desktop: connect to databases, browse their schemas, run queries, look at
and edit data. It is written in Java and draws its windows with
[rethinkj UI](https://ui.rethinkj.com), a native, GPU-rendered UI framework, whose first app it is.

Status: early. The app opens a frameless window with its menus, the workspace beside empty tabs, and
a status line that counts the open connections and the consoles that may hold uncommitted work, and
shows the version, which opens About; connections can be created, edited, duplicated and deleted,
with their passwords in the system keyring and the rest in the workspace (`workspace.json`), a tree
of folders and connections, which the first start fills from an earlier version's `connections.json`
where it is missing, and tested from their dialog, which downloads the JDBC driver from Maven
Central after asking: the recommended version, or one the connection chose from the repository's
list. The sidebar, Workspace, shows folders, nested to any depth up to 240, beside the connections:
its "+", the File menu and a folder's menu make a folder, named in a dialog, or a connection, which
go into the folder selected or that of the connection selected, and a duplicate goes right after its
original; a folder is renamed in a dialog, folders and connections move by Move to, which lists the
top and every folder by its path, or by dragging them before, after or into a folder, in any order,
and a move leaves a connection connected with its tabs; whether a folder is open is saved, and
Delete… of one that holds something asks first, with the number of its connections, and moves what
it holds up a level or deletes it, each connection as its own Delete… does after the questions of
all their consoles, and then the folder; one whose password the keyring did not delete stays, with
its folder, and the dialog names it. File, Drivers… lists the versions downloaded of each kind with
their sizes, downloads one and deletes one that no open connection uses. Opening a connection in the
tree connects and reads its databases, schemas, tables and views, and columns, one level at a time
as they are opened; several connections are open at once. A double press or Enter on a table or view
opens its rows in a tab, read late in pages on its connection's lane, with the rows in view in its
status line and buttons that scroll a page back or on, or to the first or last row; tabs move and
tear off into windows of their own. The columns of a table and of a console's result fit their first
rows once, and a table's fit again where a refresh finds other columns. A moment, a timestamp with a
time zone, shows and is copied in the computer's time zone, with the offset it has there.
Shift+Enter in a grid, or the button in its
status line, opens a panel beside it with the value of the current cell, which follows the
selection: JSON (PostgreSQL's `json` and `jsonb`, MySQL's and MariaDB's `json`) formatted and
colored, and with its first problem marked where it is not valid; a table reads a value cut short
whole by its row's primary key, a console's result keeps long values whole up to a budget and
their start beyond it, and a MongoDB console's shows the value in its document. A connection can
carry a color, which marks its row and its tabs. "New console", in a connection's menu or the File
menu, opens a tab with an SQL editor that runs the statement at the caret, the selection or all of
them on a connection of its own, with each result's rows read in portions as they scroll, a count
for a change, and a log of what ran in Messages, an error at the place the server names underlined
in the editor with the caret there, and completions of keywords and of the schemas, tables and
columns the tree read or reads for it; it switches databases and cancels what runs. It commits each
statement by itself in Auto, and in Manual Commit and Rollback end the transaction, whose state it
shows as the server says it, and as possibly open where the server has not said; closing it,
switching auto-commit or the database, disconnecting, changing or deleting its connection and
closing HooriDB ask first while one may be open. In Manual, and while a transaction is open, it
reads each result whole before showing it and keeps the first 100,000 rows of each, asking first
where the server's plan estimates more; it asks before an UPDATE or DELETE without a WHERE in any
mode; while a change of its connection is saved or deleted it runs nothing. A connection to a server
of SQL may be read-only: each of its sessions, the tree's, a console's and the planner's, is
read-only at the server from its opening, on any database, so that the server refuses what writes,
and a statement that would turn that off is not sent. What finishing a session's work reports, a
read's commit as it is given up, Commit's or Rollback's failure or warnings, a closing session's
failure, and any outcome of a call on its session that the console no longer takes, as when it was
cancelled or closed, goes to a list of notices in the main window until dismissed, and stops what
waited on it; a Commit or Rollback that fails or does not answer can be left by ending the
connection; closing a session ends within a bound, and Exit, also without the workspace's file,
waits for the sessions it ends and stays open on a failure. The system keyring's calls run one after
another; one that does not answer can be given up, and what is asked of the keyring next waits
behind it; a saved password it does not give in time is asked of the user, and where the dialog does
not know what the keyring holds, not keeping the password there deletes it. A command palette
(Control+Shift+P, or the View menu) runs the menus' commands in its window, and after `@`
(Control+P) finds the tables and views the tree has read by name and opens one, reading a database
it has not read when chosen; the menus give keys to a new console, closing a tab, the next and
previous tab, and the focus on the tree or the tab. Consoles are kept: their texts, connections and
databases are saved a moment after each change, beside the workspace, and reopened unconnected at
the next start; each statement run from a console goes into its connection's history
(`history.json`, at most 1,000 a connection, owner-only), which History lists, searches, inserts
from and clears; Save query keeps the selection, or else the whole text, under a name
(`queries.json`, owner-only), which Saved queries lists, searches, inserts from and deletes. The
statement log, a panel below the tabs that starts collapsed and that the status line, the View menu
and the palette open, lists what every connection sent, from the tree, a table's grid, the
estimates, the transactions, the consoles and the server monitor's cancels and ends, with its time,
connection, part, duration and outcome, and the chosen statement whole in its dialect's colors; it
filters by connection, part and outcome, searches, copies a statement, clears, and can be turned
off. A password in a statement or a MongoDB command is masked before it is logged, and the log is
kept beside the workspace (`statement-log.jsonl`, up to 8 MB and one older file, owner-only),
written in batches. A connection to MongoDB goes through its Java driver, downloaded as the JDBC
drivers are, with its parts beside it, and through a bridge of core's that is loaded beside the
driver; its host may name a DNS SRV record that lists the servers, as `mongodb+srv://` does, with TLS on by default and no
port; its user may be defined in a database other than `admin`, which it names (`authSource`); it
may be read-only, which sends only commands that read, as find, aggregate without `$out` or `$merge`
and counts, and refuses the others unsent. Its databases and collections show in the tree, with the
fields that a sample of their documents has; a collection opens in a tab as a grid of its fields,
read in pages, with a document or an array in a cell as Relaxed Extended JSON and a field a
document does not have as an empty cell, where a `null` shows NULL, or a page of documents at a
time as Relaxed Extended JSON or as a tree of their fields. Its console runs
statements of the shell without JavaScript (`db.c.find(…)` with its cursor's calls, `aggregate`,
counts, `distinct`, the writes, indexes, `runCommand`, `use` and `show`), shows their documents in
the same three ways, underlines where a statement goes wrong, lays its statements out with
Control+Alt+L, changing only white space, completes collections, methods, operators and fields, and
asks before an update or delete of every document; it has no transactions. "Server monitor", in a
connection's menu, the File menu or the palette, opens a tab with the server's sessions, variables,
status counters and who blocks whom, read on a session of its own at an interval that can be
changed and paused, and not while the tab is hidden; a session's statement is cancelled and a
session ended after a question, never the monitor's own, and credentials in other sessions'
statements are masked. Run from its app image,
HooriDB looks for a newer GitHub release at each start, in the channel Help chooses, Stable or
Nightly (at first that of its own version), downloads it and restarts into it through its own Exit
when asked; the new version confirms once its window has shown its first frame, and the updater's
helper puts the old version back where it does not, and a version put back is not offered again
until a higher one comes out. Help, About HooriDB shows the version that runs, its channel of
updates, its authors, license and website. Started from the unpacked Linux bundle, it puts itself
into the application menu, which gives its windows their icon in the dock. The main window's
placement, the sidebar's width and the statement log's open state, height and detail split are kept
(`ui-state.json`). The theme, System, Light or Dark in the View menu, where System follows the
computer's scheme, is kept in `settings.json`, read before the first frame. File, Settings…
(Control+,, or the palette) opens or raises a modeless settings window over the shared state, with
pages General, Appearance, Editor, Databases and History & log, where every change applies at once,
a changed row can be reset and each page has Restore defaults. Nothing can be edited,
filtered or sorted yet.

- Later home: https://db.hoori.dev
- License: MIT, as rethinkj UI's and rethinkj Updater's ([LICENSE](LICENSE)). The bundle carries it
  in `legal/`, beside the licenses of what it ships.
- Base package and Maven group: `dev.hoori.db`

## Targets

- Desktop only: Windows 11 or later and Linux, on x64. macOS, web and mobile are not targets.
- JDK 25 and Maven 3.9 or later. There is no Maven Wrapper.

## Build and run

```sh
mvn verify                                # build and test everything
mvn -q -pl app -am compile exec:exec      # start the app in a window
mvn spotless:apply                        # format the Java code
mvn -Pbundle verify                       # the release bundle of this platform (see "Releasing")
```

The first build downloads the UI framework from Maven Central's snapshot repository and needs
network access. It builds nothing native: the framework's native libraries come packaged in a JAR
per platform, which a profile of `app/pom.xml` picks by operating system.

With `-Dargs="--script <file> --out <folder>"` the app runs a script instead of opening a window and
writes images and element trees into the folder, with paths relative to the folder Maven was started
in (see "Testing"). A script never touches the user's own connections or keyring: it starts from a
new temporary folder and has no keyring, or with `--config <folder>` from the workspace in that
folder. It has no drivers either and never downloads one, so it cannot test a connection; tests give
the app a made-up repository and server (`FakeDrivers`).

In the app image, where alone it updates itself, the system property `hooridb.releases`, as in
`JAVA_TOOL_OPTIONS=-Dhooridb.releases=http://127.0.0.1:8000/`, has HooriDB read its releases from
that GitHub-compatible REST API instead of api.github.com, to try updates between two local bundles.

## Modules

| Directory | Artifact       | Base package        | Purpose                                                                                         |
| --------- | -------------- | ------------------- | ----------------------------------------------------------------------------------------------- |
| `core/`   | `hooridb-core` | `dev.hoori.db.core` | Connections, metadata and queries. No user interface: nothing here depends on the UI framework. |
| `app/`    | `hooridb-app`  | `dev.hoori.db.app`  | The desktop app: state, views and windows. `dev.hoori.db.app.HooriDb` is its `main`.            |

- Dependencies point one way: `app` depends on `core`, never the reverse. What can be tested without
  a window or a view belongs in `core`.
- A new module gets the directory `<name>/`, the artifactId `hooridb-<name>` and the base package
  `dev.hoori.db.<name>`, and is listed under `<modules>` in the root `pom.xml`.
- Shared build configuration (Java release, plugin and dependency versions) belongs in the root
  `pom.xml`; a module's POM declares only what is specific to it. Plugin versions are pinned there,
  so that every machine builds with the same ones.

## The UI framework

- Documentation: https://ui.rethinkj.com. For agents, https://ui.rethinkj.com/llms.txt lists every
  page as Markdown and https://ui.rethinkj.com/llms-full.txt holds them all. Read the page of a
  component before using it. Source: https://github.com/rethinkj/ui.
- The framework has no release yet. A property per artifact in the root `pom.xml`, such as
  `rethinkj-ui-kit.version`, names its SNAPSHOT, which changes with each push to its main branch;
  Maven looks for a newer one once a day, and `mvn -U verify` looks now. Its API still changes, so a
  build that worked yesterday may need a small change today.
- How an app is made with it: a state object and a function from that state to a tree of elements.
  Handlers change the state, and the view is built again after each input. There are no observable
  properties and no listeners.
- Components of its kit read the theme from `Theme.CURRENT`, which the app provides from the state's
  theme, and use only its tokens. Views here do the same: each reads `Theme.CURRENT.get()` once at
  its top into a local that its lambdas capture, and takes no theme as a parameter; handlers,
  background work and posted tasks run outside a build and read the state's `theme` instead. No
  color, size or font of their own where the theme has a token. The app's own color, for its icon,
  is `HooriDb.COLOR`.
- HooriDB is the framework's first real app. Where the framework lacks something or does something
  wrong, say so, with the smallest case that shows it, instead of building around it here; such
  things are fixed in the framework.
- Sort each such finding before reporting it. General: any app made with the framework would meet
  it; it is reported upstream to the framework, and the work here that waits for it is linked to the
  report. Specific to HooriDB: it makes sense only in a database tool; it is built here from the
  kit's parts, and if that needs a hook in the framework, the hook is the general wish.

## How the code is written

- Data are records: a connection's profile, a table, a column, a row of a result. The app's state is
  an ordinary mutable class that holds them, and a handler replaces a record with a new one. Records
  come back safely from other threads, compare with `equals` (which is how a form knows that it has
  changes), and work with `switch` over sealed interfaces.
- Nothing that talks to a database, the file system or the network runs on the app's thread. Such
  work goes through `App.background`, gets only values in its closure (never the state or elements),
  and its result arrives as a posted task. A view shows that it is waiting, and closing what asked
  cancels the work.
- Typed values, not strings: enums and records in choices, the kit's `Format` for numbers and dates
  in fields and grid columns, and `Editing` with a `Part` constant per field for a form over a
  record.
- What a provider does its own way (SQL, driver behavior, error codes, lexical rules) lives in its
  dialect, `provider.dialect()`: `dev.hoori.db.core.dialect`, and beside a package that the code
  needs private members of, as `data/*Rows`. Logic code does not name providers; it asks the dialect
  for a trait named for the behavior. What every provider has is `provider.traits()`; a server of
  documents has a `DocumentDialect`, `provider.documents()`, in place of a `Dialect`, and code of core
  that serves both asks `provider.keepsDocuments()` or switches over the two. The app chooses once,
  with `Language.of(provider)`, a sealed `Language`, `Sql` or `Shell`, which the console, its
  completions (`SqlCompletion`, `ShellCompletion`) and a table's tab ask or switch over; what differs
  in connecting, as the dialog's fields, switches over `provider.traits()`.
- Every statement and command that core sends goes through `dev.hoori.db.core.log.Journal`, with
  its purpose, which records it for the recorder a caller bound and masks a password first, as the
  dialect's `passwordWords` and `DocumentDialect.secretFields` say; a new send goes through it too.
  Not yet: the writes of `data.Changes` and the reads of the rows dialects' `changeable`, which
  nothing in the app calls so far. The app binds it per call on a lane's link (`Recorded`), so
  recording never waits for the disk; it binds none for the server monitor's reads, which repeat
  every few seconds and would fill the log, only for its cancels and ends (`Purpose.MONITOR`).
- The shell's syntax is lexed in one place, core's `dev.hoori.db.core.document.Lexer`: splitting
  statements (`Script.split`, `Statements`), reading them (`Shell`), coloring (`ShellSyntax`, which
  maps its tokens to `Theme.Code`), laying out (`Layout`, which Control+Alt+L runs) and completing
  (`Suggestions`) all go by its tokens. A console reads each statement once, as a `Script.Parsed`,
  when it takes it to run; the constructors of values are named once, in `Shell`.
- No MongoDB driver is on the class path. Core compiles its bridge,
  `dev.hoori.db.core.document.mongo`, against the driver as a provided dependency, and `DriverStore`
  defines that package's classes from core's file in the downloaded driver's class loader, where
  they see the driver and core; the bridge speaks to core in core's values alone (`Document`,
  `Bson`) and names none of the driver's classes in `MongoBridge` itself, so that it loads beside
  any JAR. Every command goes through `DocumentSession.command`, which is where a read-only
  connection refuses what `Commands.reads` does not let through. A read-only connection to a server
  of SQL is made so by `Connector.open`, with the dialect's `connecting().readOnly()`, and its
  `Execution` does not send what `ReadWrite` finds may turn that off.
- Credentials never go into logs, exceptions' messages, element trees or stored images.
- Code, comments, messages and docs are in American English, as the UI framework's are: color, gray,
  catalog, behavior, initialize. "Cancelled" stays as Java writes it.

## Testing

- Views are tested without a window: `Script.run(text, app, folder)` drives the app with steps such
  as `press role=button label="Connect"`, `type "…"`, `key Enter`, `frame` and `snap <name>`, and
  writes `<name>.png` and `<name>.tree.txt`. A test asserts on the tree, which says what is
  selected, checked, focused, disabled, busy or invalid, and looks at the image only where the look
  is the point. `HooriDbTest` is the first such test; the framework's documentation has every step
  under "Testing and agents".
- Selectors go by role and label, as a person would find a control, so every control that can be
  pressed or typed into has a label.
- An element fades in when it appears new in a parent that was already there, and fades out when it
  leaves; a selector does not find it meanwhile. So a script runs `advance 1` and `frame` after a
  dialog or a menu opens or closes, and after a part of an open view is replaced by another, such as
  a progress line by a callout or a tab's content by another tab's: what fades out can hide what
  came in its place. A press finds its target in the last frame, so after typing that moves the
  layout, such as an error appearing below a field, a `frame` comes before the next press.
- `await` waits for all running background work, and its results arrive only with the next `frame`.
  What running work posts with `App.post`, such as a download's progress, also arrives only at
  `await`, so a script cannot show it while the work is held open. Work that a result starts, such
  as the save after the keyring answered, needs another `await` and `frame`. To hold work open, a
  test's fake blocks on a gate it opens between two `Script.run` calls on the same app; an `await`
  in between would wait for the gate.
- The catalog's tests run against `FakeServers`: made-up servers by host, each written as an outline
  of databases, schemas, tables and columns, whose calls a test holds at a gate or fails by their
  key, such as `tables <host>/<database>.<schema>`. Each connection's calls run one at a time on its
  `Lane`, so a held call also holds the calls behind it on that connection. A held `open` fails as
  timed out once the connect timeout it was given has passed, as a driver's does.
- A script's `type "…"` types one character at a time, as a keyboard does, so the kit's completion
  list opens from a word's second letter, and a `key Enter` after it takes a suggestion. Text that
  must go in as it is, in one edit, is pasted with `clipboard "…"` and `key V control`, as the
  console's tests do through `ConsoleTest.type`. A dot typed opens it at once, and Control+Space
  anywhere. A list with nothing to show closes, so what the console reads for it shows at the next
  keystroke.
- A data grid tells the rows it built in the frame after the one that built them, so a script runs
  `frame` twice after `scroll`, and only then do the reads of those rows start. `FakeServers` makes
  up a table's rows from their position (`rows`, `values`), whose moments show in Europe/Berlin, as
  the catalog's tests set `State.zone`, whatever the machine's zone; its reads are keyed
  `read <host>/<path> <from>-<to>`, and those of a whole value, which `whole` gives,
  `whole <host>/<path> <column> <key>`, and, as core's do, fail as columns changed when the columns
  they are given are not the table's now; `FakeServers.queryFailure` makes core's typed failure.
  Its calls go through core's `Journal` as core's do, a catalog read or a page by its key and a
  console's statement by its text, so that the statement log shows them. A
  test that closes a tab or ends a connection does it before the grid's next frame and the reads in
  flight have settled, as a user can.
- A console's statements run against `FakeServers.statement(sql, results, messages…)`, rows made
  with `FakeServers.made` and failures with `serverError`; a statement it does not know fails as
  invalid. Their calls are keyed `run <host> <sql>` for the first portion and
  `next <host> <sql> <from>` for each further one, and a held one that is interrupted ends with a
  cancelled failure, as core's does. A console opens its own connection behind the tree's, so a
  server's `open` key is called twice, and once more where the first estimate of a read runs, on the
  connection that only estimates use, and once more where a table's rows are counted other than at
  its opening, on the connection that only such counts use. Each fake connection keeps a
  transaction and auto-commit as the servers do: `BEGIN`, `COMMIT` and `ROLLBACK` are known
  statements, and on MariaDB and MySQL `SET autocommit=0` or `=1` and `SET completion_type=1`;
  auto-commit off opens a transaction with any other statement, as `begins` does with one; an error fails PostgreSQL's, a failed COMMIT ends
  it there and leaves MariaDB's and MySQL's unless it is of class 40 (`rolledBack`, `deadlock()`),
  which rolls it back, DDL commits theirs, and `rollBackBehind` ends it as a server does by itself;
  a `ROLLBACK TO` a savepoint leaves it open. The server's auto-commit and the driver's idea of it
  are kept apart, as they drift in MySQL Connector/J: `turnsAutoCommitOn` turns the server's on
  behind the driver, also where the statement fails, setting it changes nothing where the driver
  thinks it is so already, and asking for the status sets the driver's from the server's, as core
  does. `cannotReport` makes a host's driver one that cannot read the status, as old MariaDB
  Connector/J. A read left with rows on PostgreSQL in Auto holds a transaction of its own with
  auto-commit off, as core's does, until it is given up, by the link's `giveUp` or `close`;
  `failsAtGiveUp` makes giving up a statement's rows fail, which fails a user's transaction on
  PostgreSQL. `hold(key, n)` lets n calls of the key pass before it holds, as a statement's own
  status before the question after it. A session's closing is background work that `await` waits
  for, so a script that exits runs `SETTLE` before it looks; the keyring fake's `late` holds its
  next call alone, which ignores its interrupt and then does what it was asked, as a native call
  can. `warnsAtGiveUp` gives a give-up a warning. A console's `outcomeWaiting` says that the lane
  has a call's outcome the console has not taken yet, the window in which a test cancels or closes.
  Core's test `Relay` stands between a connection and a real server and `freeze`s it, as a server
  that stops answering. Turning auto-commit on from off under one notes
  `commit by auto-commit <host>`, a change of it `autocommit <host> on` or `off`, and asking for the
  status is keyed `transaction <host>`. The planner's estimate of a console's read runs on that
  connection, keyed `explain <host> <sql>`, with what `plans` gives, on the database `estimatedOn`
  names; `linked` notes every call after the number of the link it ran on, so a test tells the
  console's session from the tree's.
- A server monitor's reads run against `FakeServers` too: `sessions`, `variables`, `counters` and
  `locks` give what a host's server has, besides the monitor's own session, numbered as its link;
  the reads are keyed `sessions <host>`, `variables <host>`, `status <host>` and `locks <host>`,
  a cancel `cancel <host> <operation>` and an end `end <host> <id>`.
- Updates run against `FakeUpdater`: releases published by version, the helper's result given,
  downloads and restarts recorded, each failable and the download held at its gate `downloading`.
  `State.shown()`, which `main` hands the window's `onShown`, stands for the first frame shown,
  which confirms an update.
- MongoDB has no made-up server that runs statements: core's `TestServer` of MongoDB, in Docker with
  the sample of `sample-schema/mongodb.js`, serves core's `DocumentConnectorServerTest`,
  `DocumentSessionServerTest` and `DocumentRunServerTest`, and the app's `DocumentsServerTest`,
  `DocumentConsoleServerTest`, which drive the app through `Servers.SERVER` with the real driver as
  `BudgetTest` does; the statements of the shell are tested without a server by `ScriptTest` and
  `ShellTest`, their colors by `ShellSyntaxTest`, their layout by `LayoutTest`, the console's
  completions by `DocumentConsoleTest` on a made-up MongoDB server of `FakeServers`, which knows the
  catalog alone, and Extended JSON by `ExtendedJsonTest`. Without Docker the tests against the real
  server are skipped; those without one run anyway.
- The app's `ServersServerTest` runs its seam to servers, `Servers.SERVER` on a `Lane`, against
  core's `TestServer`, which comes with core's test-jar; the JDBC drivers for such tests are copied
  by the `jdbc-drivers` execution of the root POM, which a module takes by naming
  `maven-dependency-plugin`. Without Docker these tests are skipped.
- A module's test classes run in `test.forks` JVMs at once (4, `-Dtest.forks=1` for one), in an
  order of chance. Surefire shuffles them by the seed the log names, which
  `-Dsurefire.runOrder.random.seed=` takes, and hands them to the JVMs from one queue as each comes
  free: the seed gives the same order again, but not which classes shared a JVM. Each class's
  report, `target/surefire-reports/TEST-*.xml`, names its JVM in the property `hooridb.test.store`
  and is written as the class ends, so the files' times give each JVM's sequence; with
  `-Dtest.forks=1` and the seed, all of them run in one JVM in that order. Each JVM starts its own
  servers in Docker as its tests ask for them, up to seven: the three of SQL, a second of each with
  the other TLS, and MongoDB. Its own files are the drivers' store, `target/test-driver-store-<n>`,
  and in the app the framework's natives, `target/natives-cache-<n>`; a test's other files are in
  its JUnit temporary folder, and the drivers copied into `target/jdbc-drivers` are only read. A
  test that changes static state puts it back, so that no class depends on another or on its order.
- A test of a give-up that is never answered waits out a bound of one second, core's
  `ShortEnding`, in place of `Execution.ENDING`, and fails where it lasts as long as that; one
  test, in `AbandonBoundTest`, waits out the ten seconds as shipped.
- Tests open no real windows and need no display, except the opt-in measurement of the performance
  budget below. Tests of `core` need neither the UI framework nor a database that is not started by
  the test itself.

## Performance budget

Speed is what sets HooriDB apart:

1. Start to first frame: under 1 second.
2. Opening a table of 1,000,000 rows on a local server: first rows under 300 ms.
3. Scrolling the grid: no frame over 16 ms.
4. Memory while idle: under 150 MB.

```sh
mvn -pl app -am verify -Dbudget -Dtest=BudgetTest -Dsurefire.failIfNoSpecifiedTests=false
```

`BudgetTest` prints each number beside its budget and asserts no timing; without `-Dbudget` it is
skipped. The start opens real windows and is skipped without a display, the first rows without
Docker.

- Start: HooriDB's `main` as a process of its own in a real window, with the bundle's initial heap
  (`-Xms32m`) and no connections or drivers, a warm-up and then six runs; the first frame as the
  framework logs it, in milliseconds after the process started.
- First rows: from Enter on the table in the tree to the frame that shows its first rows, without a
  window, on each of core's `TestServer`s, five times.
- Scrolling: each `App.frame()` while the wheel scrolls a grid of 1,000,000 made-up rows one step a
  frame at 60 frames a second, with the pointer over the grid. This is the app's share of a frame
  (input, build and layout) without drawing: the framework has no public way yet to drive a real
  window and time its frames.
- Memory: the resident memory (on Windows the working set) of the started process 10 seconds after
  its first frame.

## Releasing

A release is a GitHub release of the tag `vX.Y.Z`, whose commit has the version `X.Y.Z` (SemVer).
Its assets are `HooriDB-X.Y.Z-linux-x64.tar.gz`, `HooriDB-X.Y.Z-windows-x64.zip`,
`HooriDB-X.Y.Z-windows-x64-setup.exe` and `SHA256SUMS`. The archives hold the whole app folder
`HooriDB/`, a jpackage app image with its own runtime, which the helper of
[rethinkj Updater](https://github.com/rethinkj/updater) swaps whole; the Windows installer installs
it for the user alone into `%LOCALAPPDATA%\Programs\HooriDB`, on Linux it is unpacked into a folder
the user may write, such as `~/.local/opt/HooriDB`.

- `mvn -Pbundle verify` builds the bundle of the platform it runs on into `app/target/bundle`; on
  Windows, `ISCC /DVersion=X.Y.Z app/src/bundle/HooriDB.iss` makes the installer from it. The
  workflow `build.yml` does both on Linux and Windows and keeps the results as artifacts; it runs by
  hand on any branch.
- On Linux a start from the app image writes `$XDG_DATA_HOME/applications/dev.hoori.db.desktop` (by
  default `~/.local/share/applications`), which puts HooriDB into the application menu and gives the
  dock its icon, and rewrites it where it points elsewhere, as after a move; an update, which leaves
  the folder in its place, keeps it. To uninstall, delete the app folder and that file; the
  workspace with the connections in `~/.config/hooridb`, the drivers in `~/.local/share/hooridb` and
  the passwords in the system keyring stay until they are deleted too.
- The framework and the updater's helper are SNAPSHOTs, which Central deletes after 90 days. So a
  release pins each `rethinkj-*.version` property of the root `pom.xml` to its timestamped SNAPSHOT,
  as the artifact's `maven-metadata.xml` in Central's snapshot repository names it, such as
  `0.1.0-20261118.121633-120`; the artifacts of one push can differ in it. main keeps plain
  `-SNAPSHOT`.
- To release: on a branch of its own, set the version of the three POMs to `X.Y.Z` and pin the
  properties, check that `mvn -U -Pbundle verify` passes, commit, and push the tag `vX.Y.Z` of that
  commit. The workflow `release.yml` refuses a tag that is not the version and any `-SNAPSHOT` left
  in a POM, builds, and publishes the release. main then gets the next `-SNAPSHOT` version.
- A version with a SemVer pre-release part, such as `X.Y.Z-rc.1`, is a pre-release: `release.yml`
  marks its GitHub release as one, and nightlies and update channels treat it as one too, never
  offering it as stable.
- Nightlies: each night at 06:07 UTC, and by hand, `nightly.yml` builds main when it changed since
  the last nightly as `X.Y.Z-nightly.YYYYMMDDHHMM`, main's version without `-SNAPSHOT` and the UTC
  minute, set for the build alone, and publishes it as a pre-release of the tag
  `vX.Y.Z-nightly.YYYYMMDDHHMM` with the same assets, never marked Latest, which stays on the stable
  release; it keeps the 14 newest nightlies and deletes older ones with their tags. It fails while a
  stable release has main's X.Y.Z or a higher one, as after a release before main got its next
  version. `release.yml` ignores nightly tags.

## Changes

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  `<type>(<scope>): <summary>`, for example `feat(app): add the connection dialog`. The scope is the
  module when the change stays in one. Keep them short: a subject of at most 72 characters, and a
  body only when the diff does not show why.
- Java code is formatted by Palantir Java Format in its Google style (two spaces, 100 columns, one
  call of a chain per line), through Spotless, as the UI framework's is. `mvn verify` fails on a
  file that is not formatted; format before committing and do not break lines by hand against it.
- No files that describe your own work: no plans, summaries, reports or review notes. What changed
  and why goes in the commit message. Plans and status are not kept in the repository.
- Keep out of commits: secrets, tokens and connection strings with credentials; home folder and temp
  paths and machine names; names of people attached to their plans or opinions; links and numbers of
  a private issue tracker.
