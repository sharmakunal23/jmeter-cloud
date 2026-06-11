#!/usr/bin/env bash
# buildImage.sh — build the jmeter-base Docker image.
#
# Two modes:
#
#   Default: download JMeter from the Apache mirror (reproducible, slow first build).
#       ./buildImage.sh
#
#   Fast local: copy a pre-extracted JMeter dist from the host (skips the download).
#       JMETER_DIST_PATH=/path/to/apache-jmeter-5.6.3 ./buildImage.sh
#
# Resulting tag: jmeter-base:${JMETER_VERSION:-5.6.3}

set -euo pipefail

JMETER_VERSION="${JMETER_VERSION:-5.6.3}"
IMAGE_TAG="${IMAGE_TAG:-jmeter-base:${JMETER_VERSION}}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -n "${JMETER_DIST_PATH:-}" ]]; then
  if [[ ! -d "$JMETER_DIST_PATH" ]]; then
    echo "JMETER_DIST_PATH=$JMETER_DIST_PATH does not exist or is not a directory" >&2
    exit 1
  fi
  expectedName="apache-jmeter-${JMETER_VERSION}"
  basename="$(basename "$JMETER_DIST_PATH")"
  if [[ "$basename" != "$expectedName" ]]; then
    echo "JMETER_DIST_PATH must point to a directory named '${expectedName}' (got '${basename}')" >&2
    exit 1
  fi

  # Stage the dist inside the build context so COPY can see it. Symlinks
  # are not followed by docker build, so we use rsync to mirror.
  stagingDir="$SCRIPT_DIR/${expectedName}"
  trap 'rm -rf "$stagingDir"' EXIT
  rsync -a --delete "$JMETER_DIST_PATH/" "$stagingDir/"

  echo "Building $IMAGE_TAG (mode=local, dist=$JMETER_DIST_PATH)"
  docker build \
    --build-arg JMETER_VERSION="$JMETER_VERSION" \
    --build-arg JMETER_INSTALL_MODE=Local \
    -t "$IMAGE_TAG" \
    .
else
  echo "Building $IMAGE_TAG (mode=download, source=Apache mirror)"
  docker build \
    --build-arg JMETER_VERSION="$JMETER_VERSION" \
    --build-arg JMETER_INSTALL_MODE=Download \
    -t "$IMAGE_TAG" \
    .
fi

echo "Built $IMAGE_TAG"
