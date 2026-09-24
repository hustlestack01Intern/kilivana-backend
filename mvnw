#!/bin/sh
set -eu

MAVEN_VERSION="3.9.9"
DIST_NAME="apache-maven-${MAVEN_VERSION}"
ARCHIVE_NAME="${DIST_NAME}-bin.tar.gz"
MAVEN_USER_HOME="${MAVEN_USER_HOME:-${HOME}/.m2}"
DIST_DIR="${MAVEN_USER_HOME}/wrapper/dists/${DIST_NAME}"
ARCHIVE_PATH="${DIST_DIR}/${ARCHIVE_NAME}"
CHECKSUM="a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b"
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ ! -x "${DIST_DIR}/bin/mvn" ]; then
    mkdir -p "${DIST_DIR}"
    rm -f "${ARCHIVE_PATH}"
    curl --fail --location --silent --show-error \
        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/${ARCHIVE_NAME}" \
        --output "${ARCHIVE_PATH}"
    printf '%s  %s\n' "${CHECKSUM}" "${ARCHIVE_PATH}" | shasum -a 512 -c -
    tar -xzf "${ARCHIVE_PATH}" -C "${DIST_DIR}" --strip-components=1
    rm -f "${ARCHIVE_PATH}"
fi

exec "${DIST_DIR}/bin/mvn" -f "${BASE_DIR}/pom.xml" "$@"
