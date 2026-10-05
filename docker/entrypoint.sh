#!/bin/sh
# Kilivana container entrypoint.
#
# Two jobs, in order:
#   1. Hand the media volume to appuser — but only if we are root to do it.
#   2. Become appuser and exec the JVM, so PID 1 is the JVM and SIGTERM reaches it.
#
# Why the chown exists at all: Railway mounts volumes as root, and a mount *shadows*
# whatever the image put at that path. The image's own `chown /data/uploads` therefore
# does nothing once a volume is attached, and the JVM (running as uid 1000) cannot
# write. Railway's only documented permission knob is RAILWAY_RUN_UID=0, which is what
# lets step 1 run at all. See docs/deploy/railway.md and
# docs/decisions/0001-railway-deploy-and-honest-money-path.md.
#
# Running as a non-root uid (a runner that ignores RAILWAY_RUN_UID, or a plain
# `docker run` with the image already constrained) skips the chown entirely rather than
# failing: the directory is only ours to fix if we actually own it.
set -e

MEDIA_ROOT="${MEDIA_ROOT:-/data/uploads}"
JAVA_OPTS="${JAVA_OPTS:--XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0}"

# The chown below is `chown -R`, as root. MEDIA_ROOT="/" or a relative path is a
# `chown -R /` of the whole container filesystem, which is unrecoverable and takes the
# image with it. Refuse before touching anything rather than trusting the default.
# One trailing-slash strip is enough: it turns "/" into "" and "//" into "/", which is
# the only way a value can be the root other than by being exactly "/".
MEDIA_ROOT_TRIMMED="${MEDIA_ROOT%/}"
if [ "$MEDIA_ROOT_TRIMMED" = "" ] || [ "$MEDIA_ROOT_TRIMMED" = "/" ]; then
    echo "FATAL: MEDIA_ROOT='$MEDIA_ROOT' is the container root." >&2
    echo "       Refusing to run 'chown -R' against it. Set MEDIA_ROOT to the volume" >&2
    echo "       mount point, for example MEDIA_ROOT=/data/uploads." >&2
    exit 1
fi
case "$MEDIA_ROOT" in
    /*) ;;
    *)
        echo "FATAL: MEDIA_ROOT='$MEDIA_ROOT' is not an absolute path." >&2
        echo "       Refusing to 'chown -R' a path relative to the working directory." >&2
        echo "       Set MEDIA_ROOT to an absolute mount point, for example" >&2
        echo "       MEDIA_ROOT=/data/uploads." >&2
        exit 1
        ;;
esac

run_java() {
    # shellcheck disable=SC2086 -- JAVA_OPTS is a word list on purpose, same as the
    # `sh -c` ENTRYPOINT this replaces.
    exec java $JAVA_OPTS -jar /app/app.jar
}

if [ "$(id -u)" = "0" ]; then
    if [ -d "$MEDIA_ROOT" ] && [ "$(stat -c '%u:%g' "$MEDIA_ROOT" 2>/dev/null)" != "1000:1000" ]; then
        chown -R appuser:appgroup "$MEDIA_ROOT"
    fi
    # Re-entered through /bin/sh rather than executed directly, so a lost exec bit on the
    # copy (a bad COPY, a restrictive umask at build time) cannot turn a privilege drop
    # into a crash loop. The second hop reaches run_java, which is the one that execs.
    exec su-exec appuser /bin/sh "$0" "$@"
fi

run_java
