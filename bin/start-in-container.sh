#!/bin/bash

MAPR_HOME=${MAPR_HOME:-"/opt/mapr"}

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"
cd "${LIVY_HOME}"

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

LIVY_RSC_PORT_RANGE="${LIVY_RSC_PORT_RANGE:-'10000~10010'}"
LIVY_RSC_PORT_RANGE=$(echo $LIVY_RSC_PORT_RANGE | sed "s/-/~/")

REMOTE_ARCHIVES_DIR="/user/${MAPR_CONTAINER_USER}/zeppelin/archives"

LOCAL_ARCHIVES_DIR="$(getent passwd $MAPR_CONTAINER_USER | cut -d':' -f6)/zeppelin/archives"
LOCAL_ARCHIVES_ZIPDIR="${LOCAL_ARCHIVES_DIR}/zip"

log_warn() {
    echo "WARN: $@"
}
log_msg() {
    echo "MSG: $@"
}

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
hadoop_fs_get() {
    hadoop fs -get "$1" "$2" &>/dev/null
}
hadoop_fs_put() {
    hadoop fs -put "$1" "$2" &>/dev/null
}
hadoop_fs_test_e() {
    hadoop fs -test -e "$1" &>/dev/null
}

component_get_home() {
    local comp_name="$1"
    local comp_home=""
    local comp_version=""
    local comp_home_legacy=""
    if [ -e "${MAPR_HOME}/${comp_name}/${comp_name}version" ]; then
        comp_version=$(cat "${MAPR_HOME}/${comp_name}/${comp_name}version")
        comp_home="${MAPR_HOME}/${comp_name}/${comp_name}-${comp_version}"
    else
        comp_home_legacy=$(find "${MAPR_HOME}/${comp_name}/" -maxdepth 1 -name "${comp_name}-*" -type d | tail -n1)
        [ -e "${comp_home_legacy}" ] && comp_home="${comp_home_legacy}"
    fi
    echo "${comp_home}"
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
    elif ( echo "${old_value}" | grep -q -F "${property_value}" ); then
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

out_archive_local=""
out_archive_extracted=""
out_archive_remote=""
out_archive_filename=""
setup_archive() {
    local archive_path="$1"
    local archive_filename=$(basename "$archive_path")
    local archive_local=""
    local archive_remote=""
    if hadoop_fs_test_e "$archive_path"; then
        archive_remote="$archive_path"
        archive_local="${LOCAL_ARCHIVES_ZIPDIR}/${archive_filename}"
        if [ ! -e "$archive_local" ]; then
            log_msg "Copying archive from MapR-FS: ${archive_remote} -> ${archive_local}"
            hadoop_fs_get "$archive_remote" "$archive_local"
        else
            log_msg "Skip copying archive from MapR-FS as it already exists"
        fi
    elif [ -e "$archive_path" ]; then
        archive_local="$archive_path"
        archive_remote="${REMOTE_ARCHIVES_DIR}/${archive_filename}"
        # Copy archive to MapR-FS
        if ! hadoop_fs_test_e "$archive_remote"; then
            log_msg "Copying archive to MapR-FS: ${archive_local} -> ${archive_remote}"
            hadoop_fs_put "$archive_local" "$archive_remote"
        else
            log_msg "Skip copying archive to MapR-FS as it already exists"
        fi
    else
        log_err "Archive '${archive_path}' not found"
    fi
    local archive_extracted="${LOCAL_ARCHIVES_DIR}/${archive_filename}"
    if [ ! -e "$archive_extracted" ]; then
        log_msg "Extracing archive locally"
        mkdir -p "$archive_extracted"
        unzip -qq "$archive_local" -d "$archive_extracted"
    else
        log_msg "Skip extracting archive locally as it already exists"
    fi

    out_archive_local="$archive_local"
    out_archive_extracted="$archive_extracted"
    out_archive_remote=$(echo "$archive_remote" | sed "s|maprfs://||")
    out_archive_filename="$archive_filename"
}

spark_configure_custom_envs() {
    if ! hadoop_fs_test_e "/user/${MAPR_CONTAINER_USER}/"; then
        log_warn "/user/${MAPR_CONTAINER_USER} does not exist in MapR-FS"
        return 1
    fi

    local zeppelin_env_sh="$(component_get_home 'zeppelin')/conf/zeppelin-env.sh"

    hadoop_fs_mkdir_p "${REMOTE_ARCHIVES_DIR}"
    mkdir -p "$LOCAL_ARCHIVES_DIR" "$LOCAL_ARCHIVES_ZIPDIR"

    if [ -n "$ZEPPELIN_ARCHIVE_PYTHON" ]; then
        log_msg "Setting up Python archive"
        setup_archive "$ZEPPELIN_ARCHIVE_PYTHON"
        log_msg "Configuring Saprk to use custom Python"
        spark_append_property "spark.yarn.dist.archives" "maprfs://${out_archive_remote}"
        spark_set_property "spark.yarn.appMasterEnv.PYSPARK_PYTHON" "./${out_archive_filename}/bin/python"
        log_msg "Configuring Zeppelin to use custom Python with Spark interpreter"
        if [ -e "$zeppelin_env_sh" ]; then
            cat >> "$zeppelin_env_sh" <<EOF
# Following lines added by livy startup script
export PYSPARK_PYTHON='./${out_archive_filename}/bin/python'
export PYSPARK_DRIVER_PYTHON='${out_archive_extracted}/bin/python'

EOF
        fi
    else
        log_msg "Using default Python"
    fi

    if [ -n "$ZEPPELIN_ARCHIVE_PYTHON3" ]; then
        log_msg "Setting up Python 3 archive"
        setup_archive "$ZEPPELIN_ARCHIVE_PYTHON3"
        log_msg "Configuring Spark to use custom Python 3"
        spark_append_property "spark.yarn.dist.archives" "maprfs://${out_archive_remote}"
        spark_set_property "spark.yarn.appMasterEnv.PYSPARK3_PYTHON" "./${out_archive_filename}/bin/python3"
    else
        log_msg "Using default Python 3"
    fi
}


SPARK_HOME=$(component_get_home "spark")
if [ -e "${SPARK_HOME}" ]; then
    spark_configure_hive_site
    spark_configure_custom_envs
else
    log_warn '$SPARK_HOME can not be found'
fi

livy_init_confs
livy_subs_client_conf "__LIVY_HOST_IP__" "$HOST_IP"
livy_subs_client_conf "__LIVY_RSC_PORT_RANGE__" "$LIVY_RSC_PORT_RANGE"


exec "${LIVY_HOME}/bin/livy-server" start
