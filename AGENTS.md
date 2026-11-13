# HooriDB

A database manager for the desktop: connect to databases, browse their schemas, run queries, look at and edit data. It is written in Java and draws its windows with [rethinkj UI](https://ui.rethinkj.com), a native, GPU-rendered UI framework, whose first app it is.

Status: a skeleton. The app opens a frameless window with its title bar and an empty state; nothing talks to a database yet.

- Later home: https://db.hoori.dev
- Base package and Maven group: `dev.hoori.db`

## Targets

- Desktop only: Windows 11 or later and Linux, on x64. macOS, web and mobile are not targets.
- JDK 25 and Maven 3.9 or later. There is no Maven Wrapper.

## Build and run

```sh
mvn verify                                # build and test everything
mvn -q -pl app -am compile exec:exec      # start the app in a window
mvn spotless:apply                        # format the Java code
```

The first build downloads the UI framework from Maven Central's snapshot repository and needs network access. It builds nothing native: the framework's native libraries come packaged in a JAR per platform, which a profile of `app/pom.xml` picks by operating system.

With `-Dargs="--script <file> --out <folder>"` the app runs a script instead of opening a window and writes images and element trees into the folder, with paths relative to the folder Maven was started in (see "Testing").

## Modules

| Directory | Artifact | Base package | Purpose |
| --- | --- | --- | --- |
| `core/` | `hooridb-core` | `dev.hoori.db.core` | Connections, metadata and queries. No user interface: nothing here depends on the UI framework. |
| `app/` | `hooridb-app` | `dev.hoori.db.app` | The desktop app: state, views and windows. `dev.hoori.db.app.HooriDb` is its `main`. |

- Dependencies point one way: `app` depends on `core`, never the reverse. What can be tested without a window or a view belongs in `core`.
- A new module gets the directory `<name>/`, the artifactId `hooridb-<name>` and the base package `dev.hoori.db.<name>`, and is listed under `<modules>` in the root `pom.xml`.
- Shared build configuration (Java release, plugin and dependency versions) belongs in the root `pom.xml`; a module's POM declares only what is specific to it. Plugin versions are pinned there, so that every machine builds with the same ones.

## The UI framework

- Documentation: https://ui.rethinkj.com. For agents, https://ui.rethinkj.com/llms.txt lists every page as Markdown and https://ui.rethinkj.com/llms-full.txt holds them all. Read the page of a component before using it. Source: https://github.com/rethinkj/ui.
- The framework has no release yet. `rethinkj.ui.version` in the root `pom.xml` names its SNAPSHOT, which changes with each push to its main branch; Maven looks for a newer one once a day, and `mvn -U verify` looks now. Its API still changes, so a build that worked yesterday may need a small change today.
- How an app is made with it: a state object and a function from that state to a tree of elements. Handlers change the state, and the view is built again after each input. There are no observable properties and no listeners.
- Components of its kit take the theme as their first parameter and read only its tokens. Views here do the same: no colour, size or font of their own where the theme has a token. The app's own colour, for its icon and mark, is `HooriDb.COLOUR`.
- HooriDB is the framework's first real app. Where the framework lacks something or does something wrong, say so, with the smallest case that shows it, instead of building around it here; such things are fixed in the framework.

## How the code is written

- Data are records: a connection's profile, a table, a column, a row of a result. The app's state is an ordinary mutable class that holds them, and a handler replaces a record with a new one. Records come back safely from other threads, compare with `equals` (which is how a form knows that it has changes), and work with `switch` over sealed interfaces.
- Nothing that talks to a database, the file system or the network runs on the app's thread. Such work goes through `App.background`, gets only values in its closure (never the state or elements), and its result arrives as a posted task. A view shows that it is waiting, and closing what asked cancels the work.
- Typed values, not strings: enums and records in choices, the kit's `Format` for numbers and dates in fields and grid columns, and `Editing` with a `Part` constant per field for a form over a record.
- Credentials never go into logs, exceptions' messages, element trees or stored images.

## Testing

- Views are tested without a window: `Script.run(text, app, folder)` drives the app with steps such as `press role=button label="Connect"`, `type "…"`, `key Enter`, `frame` and `snap <name>`, and writes `<name>.png` and `<name>.tree.txt`. A test asserts on the tree, which says what is selected, checked, focused, disabled, busy or invalid, and looks at the image only where the look is the point. `HooriDbTest` is the first such test; the framework's documentation has every step under "Testing and agents".
- Selectors go by role and label, as a person would find a control, so every control that can be pressed or typed into has a label.
- Tests open no real windows and need no display. Tests of `core` need neither the UI framework nor a database that is not started by the test itself.

## Changes

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <summary>`, for example `feat(app): add the connection dialog`. The scope is the module when the change stays in one. Keep them short: a subject of at most 72 characters, and a body only when the diff does not show why.
- Java code is formatted by Palantir Java Format in its Google style (two spaces, 100 columns, one call of a chain per line), through Spotless, as the UI framework's is. `mvn verify` fails on a file that is not formatted; format before committing and do not break lines by hand against it.
- No files that describe your own work: no plans, summaries, reports or review notes. What changed and why goes in the commit message. Plans and status are not kept in the repository.
- Keep out of commits: secrets, tokens and connection strings with credentials; home folder and temp paths and machine names; names of people attached to their plans or opinions; links and numbers of a private issue tracker.
