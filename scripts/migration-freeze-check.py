#!/usr/bin/env python3
"""Keeps the migration freeze (intent/forward-only-migrations.md).

From the first release tag (v0.1.0) a published migration is immutable and every schema change is a new
forward migration. This check reads the repository against the latest release tag and fails when:

  * a versioned migration that exists in the tag was modified, renamed or deleted;
  * a new migration is numbered at or below the highest version the tag holds for its service (versions
    must be strictly greater; gaps are allowed), or repeats a version;
  * a new migration does something destructive (DROP TABLE/COLUMN/SCHEMA/TYPE, RENAME, ALTER COLUMN ...
    TYPE, SET NOT NULL, DELETE FROM, TRUNCATE) without a first line
        -- storeql:contract after=vX.Y.Z reason=...
    naming a release tag that exists, is reachable from HEAD and is at least one release older than the
    latest tag.

It also reads the docs that state the rule (CLAUDE.md, docs/ARCHITECTURE.md, docs/coding-standards.md,
.claude/commands/migration.md): each must carry the policy marker and none may keep the old "DEV only" wording.

Before any tag exists the fold rule still applies, so the check passes and says so. Repeatable (R__) and
afterMigrate files are exempt (they must stay idempotent).

Usage: scripts/migration-freeze-check.py              check the repository against its latest tag
       scripts/migration-freeze-check.py --self-test  break each promise in temporary repositories (and
                                                       in a clone of this one); fails unless noticed
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR_RE = re.compile(r"^((?:services|platform|shared)/[^/]+)/src/main/resources/db/migration/(V[^/]+\.sql)$")
VERSION_RE = re.compile(r"^V(\d+(?:\.\d+)*)__.+\.sql$")
MARKER_RE = re.compile(r"^--\s*storeql:contract\s+after=(v\d+\.\d+\.\d+)\s+reason=(\S.*)$")
TAG_PATTERN = "v[0-9]*.[0-9]*.[0-9]*"
POLICY_DOCS = ["CLAUDE.md", "docs/ARCHITECTURE.md", "docs/coding-standards.md", ".claude/commands/migration.md"]
POLICY_MARKER = "<!-- migration-policy:v1 -->"
STALE_WORDING = [
    "While the product is in DEV (nothing deployed)",
    "While the product is in DEV a migration only",
    "The product is in DEV and nothing is deployed",
    "rule is revisited with the owner",
    "numbers run 1..n, no gaps",
    "files `1..n` with no gaps",
]
DESTRUCTIVE = [
    (re.compile(r"\bDROP\s+(TABLE|COLUMN|SCHEMA|TYPE)\b", re.I), "DROP"),
    (re.compile(r"\bRENAME\b", re.I), "RENAME"),
    (re.compile(r"\bALTER\s+COLUMN\s+\w+\s+(SET\s+DATA\s+)?TYPE\b", re.I), "ALTER COLUMN TYPE"),
    (re.compile(r"\bSET\s+NOT\s+NULL\b", re.I), "SET NOT NULL"),
    (re.compile(r"\bDELETE\s+FROM\b", re.I), "DELETE FROM"),
    (re.compile(r"\bTRUNCATE\b", re.I), "TRUNCATE"),
]


def git(root, *args, check=True):
    r = subprocess.run(["git", "-C", root, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout.strip() if r.returncode == 0 else None


def version_of(name):
    m = VERSION_RE.match(name)
    return tuple(int(p) for p in m.group(1).split(".")) if m else None


def latest_tag(root):
    out = git(root, "tag", "--merged", "HEAD", "--list", TAG_PATTERN, "--sort=-v:refname", check=False)
    tags = out.split("\n") if out else []
    return tags[0] if tags else None


def frozen_files(root, tag):
    """{path: blob} of the versioned migrations the tag holds."""
    out = git(root, "ls-tree", "-r", tag)
    files = {}
    for line in out.split("\n"):
        meta, _, path = line.partition("\t")
        m = DIR_RE.match(path)
        if m and version_of(m.group(2)):
            files[path] = meta.split()[2]
    return files


def working_files(root):
    files = {}
    for base in ("services", "platform", "shared"):
        top = os.path.join(root, base)
        if not os.path.isdir(top):
            continue
        for svc in sorted(os.listdir(top)):
            d = os.path.join(top, svc, "src", "main", "resources", "db", "migration")
            if os.path.isdir(d):
                for n in sorted(os.listdir(d)):
                    if version_of(n):
                        files[f"{base}/{svc}/src/main/resources/db/migration/{n}"] = os.path.join(d, n)
    return files


def strip_comments(sql):
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    return re.sub(r"--[^\n]*", " ", sql)


def tag_is_older(root, after, latest):
    """after exists, is reachable from HEAD, and is strictly older than the latest tag."""
    if git(root, "rev-parse", "-q", "--verify", f"refs/tags/{after}", check=False) is None:
        return f"names release {after}, which does not exist"
    if after == latest:
        return f"names {after}, the latest release: a contract move must wait one release"
    if subprocess.run(["git", "-C", root, "merge-base", "--is-ancestor", f"refs/tags/{after}", f"refs/tags/{latest}"],
                      capture_output=True).returncode != 0:
        return f"names {after}, which is not an ancestor of {latest}"
    return None


def policy_problems(root):
    out = []
    for rel in POLICY_DOCS:
        try:
            with open(os.path.join(root, rel), encoding="utf-8") as f:
                text = f.read()
        except OSError:
            out.append(f"{rel}: missing; it states the migration policy")
            continue
        if POLICY_MARKER not in text:
            out.append(f"{rel}: does not carry the migration policy ({POLICY_MARKER}); it must say fold until the first tag, forward-only from it")
        for stale in STALE_WORDING:
            if stale in text:
                out.append(f"{rel}: still says '{stale}'; the policy is fold until the first tag, forward-only from it")
    return out


def check(root):
    tag = latest_tag(root)
    if tag is None:
        return [], "no release tag yet: the fold-into-CREATE rule still applies, nothing is frozen"
    frozen = frozen_files(root, tag)
    current = working_files(root)
    problems = []
    for path, blob in sorted(frozen.items()):
        if path not in current:
            problems.append(f"{path}: removed or renamed since {tag}; a published migration is never moved, correct it with a new one")
        elif git(root, "hash-object", current[path]) != blob:
            problems.append(f"{path}: modified since {tag}; a published migration is never edited, correct it with a new one")
    highest = {}
    for path in frozen:
        svc = DIR_RE.match(path).group(1)
        highest[svc] = max(highest.get(svc, ()), version_of(DIR_RE.match(path).group(2)))
    seen = {}
    for path, full in sorted(current.items()):
        if path in frozen:
            continue
        svc, name = DIR_RE.match(path).groups()
        v = version_of(name)
        if svc in highest and v <= highest[svc]:
            problems.append(f"{path}: version {'.'.join(map(str, v))} is not above {'.'.join(map(str, highest[svc]))}, the highest in {tag}; number it higher")
        if (svc, v) in seen:
            problems.append(f"{path}: repeats version {'.'.join(map(str, v))} of {seen[(svc, v)]}")
        seen[(svc, v)] = name
        with open(full, encoding="utf-8") as f:
            text = f.read()
        found = sorted({label for rx, label in DESTRUCTIVE if rx.search(strip_comments(text))})
        first = next((ln.strip() for ln in text.split("\n") if ln.strip()), "")
        marker = MARKER_RE.match(first)
        if found and not marker:
            problems.append(f"{path}: {', '.join(found)} is a contract move; its first line must be '-- storeql:contract after=vX.Y.Z reason=...'")
        elif marker:
            why = tag_is_older(root, marker.group(1), tag)
            if why:
                problems.append(f"{path}: the contract marker {why}")
    return problems, f"checked against {tag}: {len(frozen)} frozen migrations"


# ── self-test ────────────────────────────────────────────────────────────────

def make_repo(files, tag=None):
    d = tempfile.mkdtemp(prefix="freeze-")
    git(d, "init", "-q", "-b", "main")
    write(d, files)
    commit(d, "init", tag)
    return d


def write(d, files):
    for rel, text in files.items():
        p = os.path.join(d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)


def commit(d, msg, tag=None):
    git(d, "add", "-A")
    git(d, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", msg)
    if tag:
        git(d, "tag", tag)


def mig(svc, n, name="x", text="CREATE TABLE t (id UUID PRIMARY KEY);\n"):
    return {f"services/{svc}/src/main/resources/db/migration/V{n}__{name}.sql": text}


def self_test():
    failures = []

    def expect(label, repo, ok, contains=None):
        problems, note = check(repo)
        good = (not problems) if ok else (bool(problems) and (contains is None or any(contains in p for p in problems)))
        if not good:
            failures.append(f"{label}: wanted {'pass' if ok else 'a failure containing ' + repr(contains)}, got {problems or 'a pass'}")

    base = {**mig("a-svc", 1, "init"), **mig("a-svc", 2, "more"), **mig("b-svc", 1, "init")}

    # before any tag: nothing is frozen, an edit passes and says so
    d = make_repo(base)
    write(d, mig("a-svc", 1, "init", "CREATE TABLE t (id UUID PRIMARY KEY, extra TEXT);\n"))
    problems, note = check(d)
    if problems or "no release tag" not in note:
        failures.append(f"before a tag: wanted a no-op pass, got {problems} / {note}")
    shutil.rmtree(d)

    def tagged():
        return make_repo(base, "v0.1.0")

    d = tagged(); expect("untouched after the tag", d, True); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "add")); expect("a new higher migration", d, True); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 10, "gap")); expect("a gap in the numbering", d, True); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", "2.5", "hotfix")); expect("a dotted hotfix version", d, True); shutil.rmtree(d)
    d = tagged(); write(d, {"services/a-svc/src/main/resources/db/migration/R__views.sql": "CREATE OR REPLACE VIEW v AS SELECT 1;\n"}); expect("a repeatable file", d, True); shutil.rmtree(d)

    d = tagged(); write(d, mig("a-svc", 1, "init", "CREATE TABLE t (id UUID PRIMARY KEY, extra TEXT);\n")); expect("an edited published migration", d, False, "modified"); shutil.rmtree(d)
    d = tagged(); os.remove(os.path.join(d, "services/a-svc/src/main/resources/db/migration/V2__more.sql")); expect("a deleted migration", d, False, "removed or renamed"); shutil.rmtree(d)
    d = tagged(); src = os.path.join(d, "services/a-svc/src/main/resources/db/migration"); os.rename(os.path.join(src, "V2__more.sql"), os.path.join(src, "V2__renamed.sql")); expect("a renamed migration", d, False, "removed or renamed"); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 2, "second")); expect("a new file at an old version", d, False, "not above"); shutil.rmtree(d)
    d = tagged(); write(d, {**mig("a-svc", 3, "one"), **mig("a-svc", 3, "two")}); expect("a repeated new version", d, False, "repeats"); shutil.rmtree(d)
    d = tagged(); write(d, mig("c-svc", 1, "brand-new-service")); expect("a service with no frozen migrations may start at 1", d, True); shutil.rmtree(d)

    # destructive moves need the marker, and the marker needs a real older release
    drop = "ALTER TABLE t DROP COLUMN extra;\n"
    d = tagged(); write(d, mig("a-svc", 3, "drop", drop)); expect("DROP COLUMN with no marker", d, False, "contract move"); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "rn", "ALTER TABLE t RENAME COLUMN a TO b;\n")); expect("RENAME with no marker", d, False, "contract move"); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "nn", "ALTER TABLE t ALTER COLUMN a SET NOT NULL;\n")); expect("SET NOT NULL with no marker", d, False, "contract move"); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "del", "DELETE FROM t;\n")); expect("DELETE with no marker", d, False, "contract move"); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "ok", "-- DROP COLUMN is only in this comment\nALTER TABLE t ADD COLUMN c TEXT;\n")); expect("a destructive word in a comment", d, True); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "m", "-- storeql:contract after=v9.9.9 reason=x\n" + drop)); expect("a marker naming a release that does not exist", d, False, "does not exist"); shutil.rmtree(d)
    d = tagged(); write(d, mig("a-svc", 3, "m", "-- storeql:contract after=v0.1.0 reason=x\n" + drop)); expect("a marker naming the latest release", d, False, "latest release"); shutil.rmtree(d)
    d = tagged()
    write(d, mig("a-svc", 3, "expand")); commit(d, "release 0.2", "v0.2.0")
    write(d, mig("a-svc", 4, "contract", "-- storeql:contract after=v0.1.0 reason=column unused since 0.2\n" + drop))
    expect("a contract move one release later", d, True)
    write(d, mig("a-svc", 4, "contract", "-- storeql:contract after=v0.2.0 reason=too soon\n" + drop))
    expect("a contract move naming the latest release", d, False, "latest release")
    shutil.rmtree(d)

    # against this repository's real history, in a clone with a throwaway tag
    try:
        clone = tempfile.mkdtemp(prefix="freeze-real-")
        subprocess.run(["git", "clone", "-q", "--local", "--no-hardlinks", ROOT, clone], check=True, capture_output=True)
        head = git(ROOT, "rev-parse", "HEAD")
        git(clone, "checkout", "-q", head)
        git(clone, "tag", "v0.0.1")
        expect("this repository, tagged: untouched", clone, True)
        real = sorted(p for p in working_files(clone))
        if not real:
            failures.append("this repository: found no versioned migrations to freeze")
        else:
            victim = os.path.join(clone, real[0])
            with open(victim, "a", encoding="utf-8") as f:
                f.write("\n-- edited after the tag\n")
            expect("this repository: an edited real migration", clone, False, "modified")
            git(clone, "checkout", "-q", "--", real[0])
            svc = DIR_RE.match(real[0]).group(1)
            highest = max(version_of(DIR_RE.match(p).group(2))[0] for p in real if p.startswith(svc + "/"))
            write(clone, {f"{svc}/src/main/resources/db/migration/V{highest + 1}__selftest_add.sql": "CREATE TABLE selftest_added (id UUID PRIMARY KEY);\n"})
            expect("this repository: a new forward migration", clone, True)
            write(clone, {f"{svc}/src/main/resources/db/migration/V{highest + 1}__selftest_add.sql": "DROP TABLE selftest_added;\n"})
            expect("this repository: a destructive migration with no marker", clone, False, "contract move")
        shutil.rmtree(clone)
    except Exception as e:  # a clone that cannot be made is a failed self-test, not a skipped one
        failures.append(f"this repository's real history could not be used: {e}")

    # the docs that state the policy
    def policy_dir(mutate=None):
        pd = tempfile.mkdtemp()
        for rel in POLICY_DOCS:
            os.makedirs(os.path.dirname(os.path.join(pd, rel)) or pd, exist_ok=True)
            with open(os.path.join(pd, rel), "w", encoding="utf-8") as f:
                f.write("Migrations. " + POLICY_MARKER + "\n")
        if mutate:
            mutate(pd)
        return pd

    pd = policy_dir()
    if policy_problems(pd):
        failures.append(f"policy docs that carry the marker: wanted a pass, got {policy_problems(pd)}")
    shutil.rmtree(pd)
    pd = policy_dir(lambda d: open(os.path.join(d, "CLAUDE.md"), "w").write("Migrations. While the product is in DEV (nothing deployed) a migration only CREATEs. " + POLICY_MARKER + "\n"))
    if not any("CLAUDE.md" in p and "still says" in p for p in policy_problems(pd)):
        failures.append("a doc that keeps the old DEV-only wording: wanted it named")
    shutil.rmtree(pd)
    pd = policy_dir(lambda d: open(os.path.join(d, "docs/ARCHITECTURE.md"), "w").write("Migrations: fold.\n"))
    if not any("docs/ARCHITECTURE.md" in p and "does not carry" in p for p in policy_problems(pd)):
        failures.append("a doc that lost the policy marker: wanted it named")
    shutil.rmtree(pd)
    pd = policy_dir(lambda d: os.remove(os.path.join(d, ".claude/commands/migration.md")))
    if not any("missing" in p for p in policy_problems(pd)):
        failures.append("a policy doc that is gone: wanted it named")
    shutil.rmtree(pd)

    if failures:
        for f in failures:
            print("SELF-TEST FAILED: " + f, file=sys.stderr)
        sys.exit(1)
    print("migration freeze check: self-test passed")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        sys.exit(0)
    found, note = check(ROOT)
    found = policy_problems(ROOT) + found
    for p in found:
        print(p, file=sys.stderr)
    if found:
        print(f"migration freeze check: {len(found)} problem(s) {note}", file=sys.stderr)
        sys.exit(1)
    print(f"migration freeze check: {note}")
