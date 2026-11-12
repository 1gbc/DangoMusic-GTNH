"""Prepare the development artifact revision for a GitHub Actions push."""

import html
import json
import os
import re
import subprocess
import time
from pathlib import Path


BRANCH = "port/gtnh-1.7.10"
BRANCH_REF = f"refs/heads/{BRANCH}"
BOT_NAME = "github-actions[bot]"
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"
VERSION_FILE = Path("build.gradle.kts")
CHANGELOG = Path("CHANGELOG.md")
VERSION_LINE = re.compile(r'^version = "(\d+)\.(\d+)\.(\d+)(?:-dev(?:\.\d+)+)?"$', re.M)
HEADER = re.compile(r"(?P<type>[A-Za-z][\w-]*)(?:\([^()]+\))?(?P<breaking>!)?: \S")
BREAKING_FOOTER = re.compile(r"^BREAKING[ -]CHANGE: \S", re.M)
UNRELEASED = re.compile(r"^## 未发布[ \t]*$", re.M)
SECTION = re.compile(r"^## ", re.M)
MARKDOWN = re.compile(r"([\\`*_\[\]~])")


def git(*args):
    return subprocess.check_output(["git", *args], encoding="utf-8").strip()


def classify(message):
    subject, _, body = message.partition("\n")
    header = HEADER.match(subject)
    if BREAKING_FOOTER.search(body) or (header and header["breaking"]):
        return 3
    return {"feat": 2, "fix": 1, "perf": 1}.get(header["type"].lower(), 0) if header else 0


def update_version(level, subjects, pull_number, pull_url):
    gradle = VERSION_FILE.read_text(encoding="utf-8")
    versions = list(VERSION_LINE.finditer(gradle))
    if len(versions) != 1:
        raise ValueError("Expected one literal release or development version in build.gradle.kts")
    current = versions[0]
    major, minor, patch = map(int, current.groups())
    if level == 3:
        major, minor, patch = major + 1, 0, 0
    elif level == 2:
        minor, patch = minor + 1, 0
    else:
        patch += 1
    version = f"{major}.{minor}.{patch}-dev.1"

    changelog = CHANGELOG.read_text(encoding="utf-8")
    pending = UNRELEASED.search(changelog)
    if not pending:
        raise ValueError("Expected a '## 未发布' heading in CHANGELOG.md")
    section = SECTION.search(changelog, pending.end())
    end = section.start() if section else len(changelog)
    notes = changelog[pending.end():end].strip("\n")
    summaries = [MARKDOWN.sub(r"\\\1", html.escape(subject)) for subject in subjects]
    entry = f"## {version} · [PR #{pull_number}]({pull_url})\n\n- " + "\n- ".join(summaries)
    if notes.strip():
        entry += f"\n\n{notes}"

    VERSION_FILE.write_text(
        gradle[:current.start()] + f'version = "{version}"' + gradle[current.end():],
        encoding="utf-8",
    )
    CHANGELOG.write_text(
        changelog[:pending.start()] + f"## 未发布\n\n{entry}\n\n" + changelog[end:],
        encoding="utf-8",
    )
    return version


def merged_pull(after):
    # On the default branch GitHub returns the merged PR that introduced this SHA.
    # Allow a short delay for the push and PR association to become visible together.
    for delay in (0, 2, 4):
        time.sleep(delay)
        pulls = json.loads(subprocess.check_output([
            "gh", "api", f"repos/{os.environ['GITHUB_REPOSITORY']}/commits/{after}/pulls",
        ], encoding="utf-8"))
        for pull in pulls:
            if (pull["merged_at"] and pull["merge_commit_sha"] == after
                    and pull["base"]["ref"] == BRANCH):
                return pull
    return None


def branch_tip():
    git("fetch", "--no-tags", "origin", BRANCH_REF)
    return git("rev-parse", "FETCH_HEAD")


def commit_version(after, level, subjects, pull):
    marker = f"DangoMusic-Dev-PR: {pull['number']}"
    tip = branch_tip()
    for _ in range(5):
        git("merge-base", "--is-ancestor", after, tip)
        existing = git(
            "log", "-1", "--first-parent", "--format=%H", "--extended-regexp", f"--author={re.escape(BOT_EMAIL)}",
            f"--grep=^{marker}$", tip,
        )
        if existing:
            print(f"Reusing development version commit {existing} for PR #{pull['number']}")
            return existing

        git("switch", "--detach", tip)
        version = update_version(level, subjects, pull["number"], pull["html_url"])
        git("add", "--", str(VERSION_FILE), str(CHANGELOG))
        git("-c", f"user.name={BOT_NAME}", "-c", f"user.email={BOT_EMAIL}",
            "commit", "-m", f"chore: bump development version to {version}", "-m", marker)
        revision = git("rev-parse", "HEAD")
        push = subprocess.run(["git", "push", "--no-follow-tags", "origin", f"HEAD:{BRANCH_REF}"])
        if push.returncode == 0:
            print(f"Prepared {version} at {revision}")
            return revision

        latest = branch_tip()
        if latest == tip:
            raise RuntimeError("Version push failed without a branch update. Check the push error above.")
        tip = latest
    raise RuntimeError("The branch kept advancing during the version push. Re-run this workflow.")


def prepare(event):
    after = event["after"]
    if event["created"] or event["forced"]:
        return after
    pull = merged_pull(after)
    if not pull:
        print("This push does not match a PR merged into the development branch.")
        return after

    # The push range includes every newly introduced commit for merge and rebase,
    # and the resulting commit message for squash merges.
    before = event["before"]
    git("merge-base", "--is-ancestor", before, after)
    messages = git("log", "--reverse", "-z", "--format=%B", f"{before}..{after}").split("\0")
    changes = [(classify(message), message.split("\n", 1)[0]) for message in messages]
    level = max(rank for rank, _ in changes)
    if not level:
        print(f"PR #{pull['number']} has no version-changing commits.")
        return after
    subjects = list(dict.fromkeys(subject for rank, subject in changes if rank))
    return commit_version(after, level, subjects, pull)


if __name__ == "__main__":
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    revision = prepare(event)
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        print(f"revision={revision}", file=output)
