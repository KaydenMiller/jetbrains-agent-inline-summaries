#!/bin/sh
# Rebuilds WhyFixtureProject's own git history. Run once after cloning this repo, or any
# time WhyFixtureProject/.git is missing.
#
# The result is one commit holding the pre-agent baseline, with the working tree left at
# the agent-edited state that .why/ describes. That difference is what Rider draws as VCS
# change bars, and several RUNBOOK.md steps read those bars.
#
# The commit's author, committer and dates are pinned so the commit hash comes out as
# c98974a every time -- that is the `base` recorded in every note in .why/tasks/.
set -e
cd "$(dirname "$0")/WhyFixtureProject"

if [ -e .git ]; then
    echo "already a repository at HEAD $(git rev-parse --short HEAD); nothing to do"
    exit 0
fi

git init -q -b main
git apply -R ../baseline.patch                      # working tree -> pre-agent baseline
git add -A
GIT_AUTHOR_NAME=Fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="1787328337 -0700" \
GIT_COMMITTER_NAME=Fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_COMMITTER_DATE="1787328655 -0700" \
git commit -q -m "Pre-agent baseline: player controller, follow camera, generated terrain table"
git apply ../baseline.patch                         # working tree -> the state .why/ describes

echo "HEAD $(git rev-parse --short HEAD) (expected c98974a)"
git status --short
