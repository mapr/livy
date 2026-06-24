#!/bin/bash
set -e
set -x

SCRIPT_DIR=$(dirname "${BASH_SOURCE[0]}")
. "${SCRIPT_DIR}/_initialize_package_variables.sh"
. "${SCRIPT_DIR}/_utils.sh"

build_code() {
  mkdir -p "${BUILD_ROOT}/build"

  mvn -B clean install -DskipTests -Drat.skip=true
  project_version=$(mvn -B help:evaluate -Dexpression=project.version -q -DforceStdout)
  scala_binary_version=$(mvn -B help:evaluate -Dexpression=scala.binary.version -q -DforceStdout)
  tar -xf "assembly/target/apache-livy-${project_version}_${scala_binary_version}-bin.tar.gz" -C "${BUILD_ROOT}/build" --strip-components=1

  mkdir -p devops/buildmeta

  # Pre-seed the dependency plugin into the local repo while online, so the offline `dependency:tree -o` will work without issues
  mvn -B dependency:resolve-plugins -DskipTests -Drat.skip=true > /dev/null
  mvn -B dependency:tree -o -DskipTests -Drat.skip=true > devops/buildmeta/dependency-tree.txt
}

main() {
  echo "Cleaning '${BUILD_ROOT}' dir..."
  rm -rf "$BUILD_ROOT"

  echo "Building project..."
  build_code

  echo "Preparing directory structure..."
  setup_role "mapr-livy"

  setup_package "mapr-livy"

  echo "Building packages..."
  build_package "mapr-livy"

  echo "Resulting packages:"
  find "$DIST_DIR" -exec readlink -f {} \;
}

main
