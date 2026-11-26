#!/usr/bin/env python3
"""Prints the formkilnSpecSha256 of packages/spec at a commit of a local liuyfly/formkiln clone.

    python3 scripts/spec-digest.py ../formkiln <commit>

The digest is SHA-256 over "<relative path>\\n<SHA-256 of the file>\\n" for every file of
packages/spec/{schema,conformance,examples}, paths sorted: the same as the build's extractSpec task.
"""
import hashlib
import io
import subprocess
import sys
import tarfile

repo, commit = sys.argv[1], sys.argv[2]
sha = subprocess.run(["git", "-C", repo, "rev-parse", f"{commit}^{{commit}}"], capture_output=True, text=True, check=True).stdout.strip()
archive = subprocess.run(
    ["git", "-C", repo, "archive", "--format=tar", sha,
     "packages/spec/schema", "packages/spec/conformance", "packages/spec/examples"],
    capture_output=True, check=True).stdout
files = []
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    for member in tar.getmembers():
        if member.isfile():
            files.append((member.name.split("packages/spec/", 1)[1], tar.extractfile(member).read()))
digest = hashlib.sha256()
for path, content in sorted(files):
    digest.update(f"{path}\n{hashlib.sha256(content).hexdigest()}\n".encode())
print(f"formkilnSpecCommit={sha}")
print(f"formkilnSpecSha256={digest.hexdigest()}")
