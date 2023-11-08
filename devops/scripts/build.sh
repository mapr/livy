#!/bin/bash
set -e

SCRIPT_DIR=$(dirname "${BASH_SOURCE[0]}")
. "${SCRIPT_DIR}/_initialize_package_variables.sh"
. "${SCRIPT_DIR}/_utils.sh"

build_livy() {
  mkdir -p "${BUILD_ROOT}/build"

  mvn -B clean package -DskipTests
  project_version=$(mvn -B help:evaluate -Dexpression=project.version -q -DforceStdout)
  scala_binary_version=$(mvn -B help:evaluate -Dexpression=scala.binary.version -q -DforceStdout)
  tar -xf "assembly/target/apache-livy-${project_version}_${scala_binary_version}-bin.tar.gz" -C "${BUILD_ROOT}/build" --strip-components=1
}

main() {
  echo "Cleaning '${BUILD_ROOT}' dir..."
  rm -rf "$BUILD_ROOT"

  echo "Building project..."
  build_livy

  echo "Preparing directory structure..."
  setup_role "mapr-livy"

  setup_package "mapr-livy"

  echo "Building packages..."
  build_package "mapr-livy"

  echo "Resulting packages:"
  find "$DIST_DIR" -exec readlink -f {} \;
}

main
