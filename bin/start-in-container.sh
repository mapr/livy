#!/bin/bash

MAPR_HOME=${MAPR_HOME:-"/opt/mapr"}

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"

LIVY_CONF_FILES=(
    "${LIVY_HOME}/conf/livy-client.conf"
    "${LIVY_HOME}/conf/livy.conf"
    "${LIVY_HOME}/conf/livy-env.sh"
    "${LIVY_HOME}/conf/log4j.properties"
    "${LIVY_HOME}/conf/spark-blacklist.conf"
)
LIVY_CONF_TEMPLATES=(
    "${LIVY_HOME}/conf/livy-client.conf.container_template"
    "${LIVY_HOME}/conf/livy.conf.container_template"
    "${LIVY_HOME}/conf/livy-env.sh.template"
    "${LIVY_HOME}/conf/log4j.properties.template"
    "${LIVY_HOME}/conf/spark-blacklist.conf.template"
)


livy_init_confs() {
    local i=0
    while [ "$i" -lt "${#LIVY_CONF_FILES[@]}" ]; do
        local livy_conf_file="${LIVY_CONF_FILES[$i]}"
        local livy_conf_template="${LIVY_CONF_TEMPLATES[$i]}"
        if [ ! -e "${livy_conf_file}" ]; then
            cp "${livy_conf_template}" "${livy_conf_file}"
        fi
        i=$(expr "$i" + 1)
    done
}

livy_subs_client_conf() {
    local livy_conf="${LIVY_HOME}/conf/livy-client.conf"
    local sub="$1"
    local val="$2"
    if [ -n "${val}" ]; then
        sed -i -r "s|# (.*) ${sub}|\1 ${val}|" "${livy_conf}"
    fi
}

# Sielent "hadoop fs" calls
hadoop_fs_mkdir_p() {
    hadoop fs -mkdir -p "$1" &>/dev/null
}
hadoop_fs_put() {
    hadoop fs -put "$1" "$2" &>/dev/null
}
hadoop_fs_test_e() {
    hadoop fs -test -e "$1" &>/dev/null
}

spark_get_home() {
    local SPARK_HOME=""
    local SPARK_VERSION=""
    local spark_home_legacy=""
    if [ -e "${MAPR_HOME}/spark/sparkversion" ]; then
        SPARK_VERSION=$(cat "${MAPR_HOME}/spark/sparkversion")
        SPARK_HOME="${MAPR_HOME}/spark/spark-${SPARK_VERSION}"
    else
        # Legacy way to find SPARK_HOME
        spark_home_legacy=$(find "${MAPR_HOME}/spark/" -maxdepth 1 -name "spark-*" -type d | tail -n1)
        [ -e "${spark_home_legacy}" ] && SPARK_HOME="${spark_home_legacy}"
    fi
    echo "${SPARK_HOME}"
}

spark_get_property() {
    local spark_conf="${SPARK_HOME}/conf/spark-defaults.conf"
    local property_name="$1"
    grep "^\s*${property_name}" "${spark_conf}" | sed "s|^\s*${property_name}\s*||"
}

spark_set_property() {
    local spark_conf="${SPARK_HOME}/conf/spark-defaults.conf"
    local property_name="$1"
    local property_value="$2"
    if grep -q "^\s*${property_name}\s*" "${spark_conf}"; then
        # modify property
        sed -i -r "s|^\s*${property_name}.*$|${property_name} ${property_value}|" "${spark_conf}"
    else
        # add property
        echo "# Following line added by Livy start-in-container.sh" >> "${spark_conf}"
        echo "${property_name} ${property_value}" >> "${spark_conf}"
    fi
}

spark_append_property() {
    local spark_conf="${SPARK_HOME}/conf/spark-defaults.conf"
    local property_name="$1"
    local property_value="$2"
    local old_value=$(spark_get_property "${property_name}")
    local new_value=""
    if [ -z "${old_value}" ]; then
        # new value
        new_value="${property_value}"
    elif ( echo "${old_value}" | grep -q -F "${new_value}" ); then
        # nothing to do
        new_value="${old_value}"
    else
        # modify value
        new_value="${old_value},${property_value}"
    fi
    spark_set_property "${property_name}" "${new_value}"
}

spark_configure_hive_site() {
    local spark_conf="${SPARK_HOME}/conf/spark-defaults.conf"
    local spark_hive_site="${SPARK_HOME}/conf/hive-site.xml"
    if [ ! -e "${spark_hive_site}" ]; then
        cp "${LIVY_HOME}/conf.new/hive-site.xml.stub" "${spark_hive_site}"
    fi
    local spark_yarn_dist_files=$(spark_get_property "spark.yarn.dist.files")
    # Check if no "hive-site.xml" in "spark.yarn.dist.files"
    if ! spark_get_property "spark.yarn.dist.files" | grep -q "hive-site.xml"; then
        spark_append_property "spark.yarn.dist.files" "${spark_hive_site}"
    fi
}

spark_configure_pyspark() {
    local spark_conf="${SPARK_HOME}/conf/spark-defaults.conf"
    local env_path_local="$1"
    local env_filename=$(basename "${env_path_local}")
    local env_path_remote="${ZEPPELIN_ARCHIVES_DIR}/${env_filename}"
    if ! hadoop_fs_test_e "${env_path_remote}"; then
        hadoop_fs_put "${env_path_local}" "${env_path_remote}"
    fi
    spark_append_property "spark.yarn.dist.archives" "maprfs://${env_path_remote}#ZEP_PYSPARK_PYTHON"
    spark_set_property "spark.yarn.appMasterEnv.PYSPARK_PYTHON" "./ZEP_PYSPARK_PYTHON/bin/python2"
}

spark_configure_pyspark3() {
    local spark_conf="${SPARK_HOME}/conf/spark-defaults.conf"
    local env_path_local="$1"
    local env_filename=$(basename "${env_path_local}")
    local env_path_remote="${ZEPPELIN_ARCHIVES_DIR}/${env_filename}"
    if ! hadoop_fs_test_e "${env_path_remote}"; then
        hadoop_fs_put "${env_path_local}" "${env_path_remote}"
    fi
    spark_append_property "spark.yarn.dist.archives" "maprfs://${env_path_remote}#ZEP_PYSPARK3_PYTHON"
    spark_set_property "spark.yarn.appMasterEnv.PYSPARK3_PYTHON" "./ZEP_PYSPARK3_PYTHON/bin/python3"
}

spark_configure_custom_envs() {
    if ! hadoop_fs_test_e "/user/${MAPR_CONTAINER_USER}/"; then
        echo "/user/${MAPR_CONTAINER_USER} does not exist in MapR-FS"
        return 1
    fi
    ZEPPELIN_ARCHIVES_DIR="/user/${MAPR_CONTAINER_USER}/zeppelin/archives"
    hadoop_fs_mkdir_p "${ZEPPELIN_ARCHIVES_DIR}"

    if [ -n "${ZEPPELIN_PYSPARK_ARCHIVE}" ]; then
        spark_configure_pyspark "${ZEPPELIN_PYSPARK_ARCHIVE}"
    fi

    if [ -n "${ZEPPELIN_PYSPARK3_ARCHIVE}" ]; then
        spark_configure_pyspark3 "${ZEPPELIN_PYSPARK3_ARCHIVE}"
    fi
}


SPARK_HOME=$(spark_get_home)
if [ -e "${SPARK_HOME}" ]; then
    spark_configure_hive_site
    spark_configure_custom_envs
else
    echo '$SPARK_HOME can not be found'
fi

livy_init_confs
livy_subs_client_conf "__LIVY_HOST_IP__" "${HOST_IP}"


exec "${LIVY_HOME}/bin/livy-server" start
