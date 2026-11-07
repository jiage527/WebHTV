#!/usr/bin/env python3
"""Restore the local-only `docs/` working directory from Git history.

`docs/` is intentionally untracked (see .gitignore and commit e519fdef); its
files exist only in history plus whatever copy a developer happens to have on
disk. A fresh clone therefore starts with an empty `docs/`, while tracked files
such as README.md and AGENTS.md still link into it, so those links are dead on
arrival and the task-record convention described by AGENTS.md has nothing to
attach to.

This script rebuilds that directory from the newest blob available in history
for every path that ever existed under `docs/`. Content is byte-identical to the
stored blobs, so restored files are reproducible rather than hand-recovered.

Usage:
    python3 scripts/restore_docs.py             # restore, then verify
    python3 scripts/restore_docs.py --verify    # verify only, write nothing
    python3 scripts/restore_docs.py --list      # list restorable paths
    python3 scripts/restore_docs.py --check-links
                                                # report tracked links into docs/
                                                # that no blob can satisfy

Exit codes: 0 ok, 1 verification/link failure.
"""

import argparse
import io
import posixpath
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = "docs"
GIT = ["git", "-c", "core.quotepath=false"]


def git(*args, binary=False, check=False):
    result = subprocess.run(GIT + list(args), cwd=ROOT, capture_output=True)
    if check and result.returncode != 0:
        raise SystemExit(
            "git {} failed: {}".format(" ".join(args), result.stderr.decode("utf-8", "replace"))
        )
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def history_commits():
    """Commits that touched docs/, newest first."""
    out = git("log", "--all", "--format=%H", "--", DOCS + "/")
    return [line for line in out.splitlines() if line.strip()]


def newest_blobs():
    """Map every docs/ path that ever existed to its newest reachable blob.

    Walking commits newest-first and taking the first blob seen per path yields
    the most recent stored revision, including paths whose individual history is
    not reachable through a plain path-limited log.
    """
    best = {}
    for commit in history_commits():
        entries = git("ls-tree", "-r", "-z", commit, DOCS + "/")
        for entry in entries.split("\0"):
            if not entry.strip():
                continue
            meta, path = entry.split("\t", 1)
            mode, otype, sha = meta.split()
            if otype != "blob" or mode == "160000":
                continue
            best.setdefault(path, sha)
    return best


def blob_bytes(sha):
    return git("cat-file", "blob", sha, binary=True)


def disk_sha(path):
    result = subprocess.run(
        GIT + ["hash-object", "--no-filters", "--", str(path)],
        cwd=ROOT,
        capture_output=True,
    )
    return result.stdout.decode().strip()


def restore(blobs):
    written = replaced = 0
    for path, sha in sorted(blobs.items()):
        data = blob_bytes(sha)
        target = ROOT / path
        if target.exists() and target.read_bytes() == data:
            replaced += 1
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        written += 1
    return written, replaced


def verify(blobs):
    ok, bad = 0, []
    for path, sha in sorted(blobs.items()):
        target = ROOT / path
        if not target.exists():
            bad.append(("missing", path))
            continue
        if disk_sha(target) == sha:
            ok += 1
        else:
            bad.append(("diverged", path))
    return ok, bad


def tracked_links():
    """Tracked Markdown links that point at repo-relative files, keyed by target.

    External URLs and pure fragment anchors carry no local file, so they are not
    repo-relative documentation and are skipped.
    """
    pattern = re.compile(r"\]\(([^)\s]+)\)")
    links = {}
    for name in git("ls-files", "*.md").splitlines():
        if not name.strip():
            continue
        text = (ROOT / name).read_text(encoding="utf-8", errors="replace")
        for match in pattern.finditer(text):
            raw = match.group(1).replace("\\", "/").split("#")[0].strip()
            if not raw or "://" in raw or raw.startswith(("mailto:", "tel:", "#")):
                continue
            # resolve the link relative to the file that contains it
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), raw))
            links.setdefault(resolved, set()).add(name)
    return links


def check_links(blobs):
    links = tracked_links()
    dead = []
    for target, sources in sorted(links.items()):
        if target in blobs or (ROOT / target).exists():
            continue
        dead.append((target, sorted(sources)))
    return links, dead


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="verify only; write nothing")
    parser.add_argument("--list", action="store_true", help="list restorable paths and exit")
    parser.add_argument("--check-links", action="store_true", help="report dead tracked links into docs/")
    args = parser.parse_args()

    stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stdout = stdout

    blobs = newest_blobs()
    if not blobs:
        print("no docs/ blobs found in history; nothing to restore")
        return 1

    if args.list:
        for path in sorted(blobs):
            print(path)
        print("total: {}".format(len(blobs)))
        return 0

    status = 0

    if args.check_links:
        links, dead = check_links(blobs)
        print("tracked repo-relative links: {}".format(len(links)))
        for target, sources in dead:
            print("  DEAD {}  <- {}".format(target, ", ".join(sources)))
            status = 1
        if not dead:
            print("  every tracked repo-relative link resolves")
        if not args.verify:
            return status

    if not args.verify:
        written, unchanged = restore(blobs)
        print("restored {} file(s), {} already current".format(written, unchanged))

    ok, bad = verify(blobs)
    print("verified byte-identical: {} of {}".format(ok, len(blobs)))
    for kind, path in bad:
        print("  {} {}".format(kind.upper(), path))
        status = 1
    return status


if __name__ == "__main__":
    raise SystemExit(main())
