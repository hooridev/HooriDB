#!/usr/bin/env python3
"""Splits the test classes over CI's shards and checks that the shards ran each of them once.

  ci.py plan --shards N --shard K [--times FILE] [--tests all|A,B,...]
      Prints the outputs of shard K: tests=<comma list of its classes> and servers=true|false.
  ci.py check --tests A,B,...
      After the tests: the shard's Surefire reports are exactly its classes, none failed, and no
      test skipped for want of Docker.
  ci.py times [--previous FILE]
      Prints the seconds of each class in this checkout's Surefire reports, as JSON. With the
      times the shard was balanced by, the first class of a module to use servers keeps its
      former time, and what it took more goes to the start of the servers.
  ci.py merge FILE...
      Prints the times of the shards as one file, which must hold every test class.
  ci.py budget NAME SECONDS LIMIT
      Prints the time, and fails where it is over the budget.
  ci.py self-test
"""

import argparse
import json
import pathlib
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

# Surefire's default includes; the POMs name none of their own.
INCLUDES = re.compile(r"^(Test.*|.*Test|.*Tests|.*TestCase)\.java$")
TESTS = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
IMPORT = re.compile(r"^import\s+([\w.]+)\s*;", re.M)
# The file's own type, after its annotations and modifiers, up to its body.
DECLARATION = (
    r"^[ \t]*(?P<modifiers>(?:@\w+(?:\([^)]*\))?\s+|[\w-]+\s+)*?)"
    r"(?P<kind>class|interface|enum|record|@interface)\s+{name}\b(?P<rest>[^{{]*)\{{"
)
# A class not yet timed on main, such as a new test.
DEFAULT_SECONDS = 5.0
# Each JVM, one a module, starts its own servers in the first class that uses them. The key holds
# what main measured; until it has, the 11-15 s that the first sharded runs took.
SERVER_START = "(server start)"
DEFAULT_SERVER_START = 12.0
NO_DOCKER = "Docker is not available"


def discover(root):
    """Every class Surefire runs: a file it includes, of a concrete class with tests of its own or
    inherited from a class or interface among the module's tests."""
    types = {}  # fqcn -> (module, included, concrete, tests, servers, supertypes)
    for java in sorted(root.glob("*/src/test/java/**/*.java")):
        text = java.read_text(encoding="utf-8")
        parts = java.relative_to(root).parts
        package = ".".join(parts[4:-1])
        head = re.search(DECLARATION.format(name=re.escape(java.stem)), text, re.M)
        if not head:
            continue
        imports = {i.rsplit(".", 1)[1]: i for i in IMPORT.findall(text)}
        supertypes = []
        for clause in re.findall(
            r"\b(?:extends|implements)\s+(.+?)(?=\b(?:implements|permits)\b|$)", head["rest"], re.S
        ):
            for t in re.sub(r"<[^<>]*>", "", re.sub(r"<[^<>]*>", "", clause)).split(","):
                if t := t.strip():
                    supertypes.append(t if "." in t else imports.get(t, f"{package}.{t}"))
        concrete = head["kind"] == "class" and "abstract" not in head["modifiers"].split()
        types[f"{package}.{java.stem}"] = (
            parts[0],
            bool(INCLUDES.match(java.name)),
            concrete,
            bool(TESTS.search(text)),
            "TestServer" in text or "Servers.SERVER" in text,
            supertypes,
        )

    def has(fqcn, trait, seen=()):
        """Whether the type, or a supertype of it among the tests, has the trait."""
        t = types.get(fqcn)
        return t is not None and fqcn not in seen and (
            t[trait] or any(has(s, trait, (*seen, fqcn)) for s in t[5])
        )

    classes, servers, modules = [], set(), {}
    for fqcn, (module, included, concrete, *_) in sorted(types.items()):
        if included and concrete and has(fqcn, 3):
            classes.append(fqcn)
            modules[fqcn] = module
            if has(fqcn, 4):
                servers.add(fqcn)
    return classes, servers, modules


def partition(classes, times, shards, servers=frozenset(), modules=None):
    """Greedy, longest first, onto the shard it ends least loaded on, counting the start of the
    servers where it is a module's first class to use them there; ties go by name and by shard."""
    seconds = {c: float(times.get(c, DEFAULT_SECONDS)) for c in classes}
    start = float(times.get(SERVER_START, DEFAULT_SERVER_START))
    slices = [[] for _ in range(shards)]
    loads = [0.0] * shards
    started = set()

    def cost(c, k):
        new = c in servers and (k, (modules or {}).get(c)) not in started
        return seconds[c] + (start if new else 0.0)

    for c in sorted(classes, key=lambda c: (-seconds[c], c)):
        k = min(range(shards), key=lambda k: (loads[k] + cost(c, k), k))
        loads[k] += cost(c, k)
        slices[k].append(c)
        if c in servers:
            started.add((k, (modules or {}).get(c)))
    every = [c for s in slices for c in s]
    assert sorted(every) == sorted(set(classes)) and len(every) == len(classes), "not a partition"
    return [sorted(s) for s in slices], loads


def selected(tests, classes):
    if tests == "all":
        return classes
    wanted = [t for t in tests.split(",") if t]
    unknown = sorted(set(wanted) - set(classes))
    if unknown:
        sys.exit(f"Not test classes Surefire runs: {', '.join(unknown)}")
    return sorted(set(wanted))


def reports(root):
    return sorted(root.glob("*/target/surefire-reports/TEST-*.xml"))


def plan(args):
    root = pathlib.Path(".")
    classes, servers, modules = discover(root)
    times = json.loads(pathlib.Path(args.times).read_text()) if args.times else {}
    chosen = selected(args.tests, classes)
    slices, loads = partition(chosen, times, args.shards, servers, modules)
    assert sorted(c for s in slices for c in s) == sorted(chosen), "the shards miss classes"
    mine = slices[args.shard - 1]
    for k, (s, load) in enumerate(zip(slices, loads), 1):
        print(f"shard {k}: {len(s)} classes, {load:.1f} s", file=sys.stderr)
    print(f"tests={','.join(mine)}")
    print(f"servers={'true' if servers & set(mine) else 'false'}")


def check(args):
    expected = {t for t in args.tests.split(",") if t}
    found, problems = set(), []
    for report in reports(pathlib.Path(".")):
        suite = ET.parse(report).getroot()
        found.add(suite.get("name"))
        if int(suite.get("failures", 0)) or int(suite.get("errors", 0)):
            problems.append(f"{suite.get('name')} failed")
        for skipped in suite.iter("skipped"):
            if NO_DOCKER in (skipped.get("message") or "") + (skipped.text or ""):
                problems.append(f"{suite.get('name')} skipped a test: {NO_DOCKER}")
                break
    if found - expected:
        problems.append(f"Reports of classes not in this shard: {', '.join(sorted(found - expected))}")
    if expected - found:
        problems.append(f"No reports of: {', '.join(sorted(expected - found))}")
    for p in problems:
        print(f"::error::{p}")
    print(f"{len(found)} reports, {len(expected)} classes expected")
    return 1 if problems else 0


def measured(root, previous, servers):
    """The seconds of each class, and the starts of the servers that the previous times show."""
    seconds, starts, first = {}, [], {}
    for report in reports(root):
        suite = ET.parse(report).getroot()
        name, took = suite.get("name"), float(suite.get("time", 0))
        seconds[name] = round(took, 3)
        # A report is written as its class ends: the class that began first began the servers.
        began = report.stat().st_mtime - took
        module = report.relative_to(root).parts[0]
        if name in servers and (module not in first or began < first[module][0]):
            first[module] = (began, name)
    for _, name in first.values():
        if name in previous and seconds[name] > previous[name]:
            starts.append(round(seconds[name] - previous[name], 3))
            seconds[name] = previous[name]
    return seconds, starts


def times(args):
    root = pathlib.Path(".")
    previous = json.loads(pathlib.Path(args.previous).read_text()) if args.previous else {}
    seconds, starts = measured(root, previous, discover(root)[1])
    if args.previous:
        seconds[SERVER_START] = starts
    print(json.dumps(seconds, indent=0, sort_keys=True))


def merge(args):
    seconds, starts = {}, []
    for f in args.files:
        shard = json.loads(pathlib.Path(f).read_text())
        starts += shard.pop(SERVER_START, [])
        seconds.update(shard)
    classes, _, _ = discover(pathlib.Path("."))
    missing = sorted(set(classes) - set(seconds))
    if missing:
        sys.exit(f"No times of: {', '.join(missing)}")
    if starts:
        seconds[SERVER_START] = sorted(starts)[len(starts) // 2]
    print(json.dumps(seconds, indent=0, sort_keys=True))


def budget(args):
    over = args.seconds > args.limit
    print(f"{'::error::' if over else ''}{args.name}: {args.seconds:.1f} s of {args.limit:g} s")
    return 1 if over else 0


# Classes for discover: some inherit their tests, and Surefire runs none of the others.
SAMPLE = {
    "m/src/test/java/p/Base.java": "package p;\n@Tag(\"x\")\npublic abstract class Base {\n"
    "  @Test void a() { TestServer.of(null); }\n}\n",
    "m/src/test/java/p/SubTest.java": "package p;\nfinal class SubTest extends Base {}\n",
    "m/src/test/java/p/Mixin.java": "package p;\ninterface Mixin { @Test default void b() {} }\n",
    "m/src/test/java/p/MixinTest.java": "package p;\nclass MixinTest implements Mixin, Runnable {\n"
    "  public void run() {}\n}\n",
    "m/src/test/java/p/OwnTest.java": "package p;\nclass OwnTest {\n  @ParameterizedTest void c() {}\n}\n",
    "m/src/test/java/p/AbstractTest.java": "package p;\nabstract class AbstractTest { @Test void d() {} }\n",
    "m/src/test/java/p/HelperTest.java": "package p;\nclass HelperTest { void e() {} }\n",
    "m/src/test/java/p/Fixture.java": "package p;\nclass Fixture { @Test void f() {} }\n",
    "m/src/test/java/q/FarTest.java": "package q;\nimport p.Base;\nclass FarTest\n    extends Base {}\n",
}


def self_test(_):
    slices, loads = partition(["a", "b", "c", "d", "e"], {"a": 5, "b": 4, "c": 3, "d": 3}, 2)
    assert slices == [["a", "b"], ["c", "d", "e"]], slices  # e takes the default 5 s
    assert loads == [9.0, 11.0], loads
    assert partition(["a"], {}, 3)[0] == [["a"], [], []]
    assert partition(["b", "a"], {}, 2) == partition(["a", "b"], {}, 2), "order matters"
    # x and y share the servers' start of 3 s on one shard rather than pay it twice.
    t = {"x": 2, "y": 2, "z": 5, SERVER_START: 3}
    slices, loads = partition(["x", "y", "z"], t, 2, {"x", "y"}, {"x": "m", "y": "m"})
    assert slices == [["z"], ["x", "y"]] and loads == [5.0, 7.0], (slices, loads)
    with tempfile.TemporaryDirectory() as folder:
        for path, text in SAMPLE.items():
            (pathlib.Path(folder) / path).parent.mkdir(parents=True, exist_ok=True)
            (pathlib.Path(folder) / path).write_text(text, encoding="utf-8")
        classes, servers, modules = discover(pathlib.Path(folder))
        assert classes == ["p.MixinTest", "p.OwnTest", "p.SubTest", "q.FarTest"], classes
        assert servers == {"p.SubTest", "q.FarTest"} and modules["q.FarTest"] == "m", servers
    classes, servers, _ = discover(pathlib.Path("."))
    assert "dev.hoori.db.core.query.QueryServerTest" in classes, "discovery finds no tests"
    assert "dev.hoori.db.core.TestServer" not in classes, "a helper counts as a test"
    assert "dev.hoori.db.core.query.QueryServerTest" in servers
    assert selected("all", classes) == classes
    print(f"self-test passed: {len(classes)} classes, {len(servers)} with servers")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(required=True)
    p = sub.add_parser("plan")
    p.add_argument("--shards", type=int, required=True)
    p.add_argument("--shard", type=int, required=True)
    p.add_argument("--times")
    p.add_argument("--tests", default="all")
    p.set_defaults(run=plan)
    p = sub.add_parser("check")
    p.add_argument("--tests", required=True)
    p.set_defaults(run=check)
    p = sub.add_parser("times")
    p.add_argument("--previous")
    p.set_defaults(run=times)
    p = sub.add_parser("merge")
    p.add_argument("files", nargs="+")
    p.set_defaults(run=merge)
    p = sub.add_parser("budget")
    p.add_argument("name")
    p.add_argument("seconds", type=float)
    p.add_argument("limit", type=float)
    p.set_defaults(run=budget)
    sub.add_parser("self-test").set_defaults(run=self_test)
    args = parser.parse_args()
    if getattr(args, "shard", 1) not in range(1, getattr(args, "shards", 1) + 1):
        parser.error("--shard is not one of the shards")
    sys.exit(args.run(args) or 0)


if __name__ == "__main__":
    main()
