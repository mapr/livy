#!/bin/sh

MAPR_HOME=${MAPR_HOME:-/opt/mapr}

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"


get_spark_home() {
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


# Copy hive-site.xml in Spark and set spark.yarn.dist.files in spark-defaults.conf
SPARK_HOME=$(get_spark_home)
if [ -e "${SPARK_HOME}" ]; then
    SPARK_CONF="${SPARK_HOME}/conf/spark-defaults.conf"
    SPARK_HIVE_SITE="${SPARK_HOME}/conf/hive-site.xml"

    if [ ! -e "${SPARK_HIVE_SITE}" ]; then
        cp "${LIVY_HOME}/conf.new/hive-site.xml.stub" "${SPARK_HIVE_SITE}"
    fi

    if grep -q "spark\.yarn\.dist\.files.*hive-site\.xml" "${SPARK_CONF}"; then
        # hive-site.xml aready in spark.yarn.dist.files
        :
    elif ! grep -q "spark\.yarn\.dist\.files" "${SPARK_CONF}"; then
        # Add spark.yarn.dist.files property
        echo "# Following line added by Livy start-in-container.sh" >> "${SPARK_CONF}"
        echo "spark.yarn.dist.files ${SPARK_HIVE_SITE}" >> "${SPARK_CONF}"
    else
        # Modify spark.yarn.dist.files property
        sed -i -r "s|^.*(spark\.yarn\.dist\.files)\s+(.*)$|\1 \2,${SPARK_HIVE_SITE}|" "${SPARK_CONF}"
    fi
fi


cp "${LIVY_HOME}/conf/livy.conf.container_template" "${LIVY_HOME}/conf/livy.conf"


exec "${LIVY_HOME}/bin/livy-server" start
