#!/usr/bin/env sh

if [ "$(uname -s)" = "Darwin" ]; then
    SCRIPT_ABS_PATH="$0"
else
    SCRIPT_ABS_PATH=$(readlink -f "$0")
fi

SCRIPT_ABS_DIR=$(dirname "$0")
SCRIPT="$SCRIPT_ABS_DIR"/c2cpg

if [ ! -f "$SCRIPT" ]; then
    echo "Unable to find $SCRIPT, have you created the distribution?"
    exit 1
fi

exec $SCRIPT -J-XX:+UseG1GC -J-XX:CompressedClassSpaceSize=128m -Dlog4j.configurationFile="$SCRIPT_ABS_DIR"/conf/log4j2.xml "$@"
