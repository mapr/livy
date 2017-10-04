#!/bin/sh

MAPR_HOME=${MAPR_HOME:-/opt/mapr}

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"

exec "${LIVY_HOME}/bin/livy-server" start
