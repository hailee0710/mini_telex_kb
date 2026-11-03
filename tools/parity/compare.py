#!/usr/bin/env python3
"""Diffs the Vikey engine against mini_qwerty_kb's TelexProcessor.

  ./setup.sh                       # once: clone Vikey, build harness + Kotlin dumper
  ./gen_corpus.py                  # once: write corpus_hand.txt / corpus_matrix.txt
  ./compare.py corpus_hand.txt     # defaults: --modern=0 --teencode=0 --autoswitch=1

Prints a class summary plus a markdown table of every disagreeing row, and writes the
full table to diff.csv. Classes compare Vikey against TelexProcessor *live* mode (no
dictionary); `mini(commit)` is shown alongside to expose dictionary-only differences.
"""
import argparse
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path

HERE = Path(__file__).parent
HARNESS = HERE / "harness"
DICT = HERE / "../../app/src/main/assets/vi_words.txt"
ENV_FILE = HERE / "build.env"


def kotlin_stdlib() -> str:
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            if line.startswith("STD_JAR="):
                return line.split("=", 1)[1]
    sys.exit("STD_JAR not found — run ./setup.sh first")


def run(cmd, corpus):
    with open(corpus, encoding="utf-8") as f:
        p = subprocess.run(cmd, stdin=f, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"{cmd[0]} failed: {p.stderr[:2000]}")
    return p.stdout


def vikey(corpus, flags):
    out = {}
    for line in run([str(HARNESS)] + flags, corpus).splitlines():
        k, _, v = line.partition("\t")
        out[k] = v
    return out


def mini(corpus, dict_path):
    live, commit = {}, {}
    for line in run(["java", "-cp", f"{HERE}/out:{kotlin_stdlib()}", "DumpKt", str(dict_path)], corpus).splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        live[parts[0]], commit[parts[0]] = parts[1], parts[2]
    return live, commit


def nfd(s):
    return unicodedata.normalize("NFD", s.lower())


def base(s):
    return "".join(c for c in nfd(s) if not unicodedata.combining(c))


def marks(s):
    return "".join(sorted(c for c in nfd(s) if unicodedata.combining(c)))


def classify(raw, v, live):
    if live == v:
        return "match"
    if base(live) == base(v) and marks(live) == marks(v):
        return "orthography (same letters+marks, different placement)"
    if base(live) == base(v):
        return "tone application differs"
    if v == raw and live == raw:
        return "both literal"
    if v == raw:
        return "vikey literal, mini toned"
    if live == raw:
        return "mini literal, vikey toned"
    if base(live) != base(v):
        return "different letters"
    return "other"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus")
    for name, values in (("modern", ("0", "1")), ("teencode", ("0", "1")), ("autoswitch", ("0", "1")),
                         ("spelling", ("0", "1")), ("restore", ("0", "1")), ("style", ("0", "1", "2"))):
        for value in values:
            ap.add_argument(f"--{name}={value}", dest="flags", action="append_const", const=f"--{name}={value}")
    ap.add_argument("--jni-keys", dest="flags", action="append_const", const="--jni-keys",
                    help="emulate the current vikey_jni.cpp key mapping")
    ap.add_argument("--all", action="store_true", help="print every row, not just disagreements")
    ap.add_argument("--dict", default=str(DICT), help="word list for mini's commit-time check")
    ap.add_argument("--out", default=str(HERE / "diff.csv"))
    a = ap.parse_args()

    flags = {"--modern=0", "--teencode=0", "--autoswitch=1"}   # parity baseline, see README
    for f in a.flags or []:
        flags = {x for x in flags if x.split("=")[0] != f.split("=")[0]}
        flags.add(f)
    flags = sorted(flags)
    v = vikey(a.corpus, flags)
    live, commit = mini(a.corpus, a.dict)
    order = [l for l in Path(a.corpus).read_text(encoding="utf-8").splitlines()
             if l and not l.startswith("#")]

    counts = Counter()
    rows = []
    for raw in order:
        if raw not in v or raw not in commit:
            counts["missing"] += 1
            continue
        c = classify(raw, v[raw], live[raw])
        counts[c] += 1
        rows.append((raw, v[raw], live[raw], commit[raw], c))

    commit_match = sum(1 for r in rows if r[1] == r[3])
    print(f"# flags: {' '.join(flags)}   corpus: {Path(a.corpus).name}   rows: {len(rows)}")
    print(f"# engine: {len(rows) - (len(rows) - counts['match'])} match / "
          f"{len(rows) - counts['match']} differ   |   commit (with dict): {commit_match} match / "
          f"{len(rows) - commit_match} differ")
    for k, n in counts.most_common():
        print(f"{n:6d}  {k}")
    print()
    print("| input | vikey | mini(live) | mini(commit) | class |")
    print("|---|---|---|---|---|")
    for r in rows:
        if a.all or r[4] != "match":
            print("| `{}` | {} | {} | {} | {} |".format(*(x if x else "∅" for x in r)))

    Path(a.out).write_text(
        "input,vikey,mini_live,mini_commit,class\n" +
        "\n".join(','.join('"' + str(x).replace('"', '""') + '"' for x in r) for r in rows),
        encoding="utf-8")


if __name__ == "__main__":
    main()
