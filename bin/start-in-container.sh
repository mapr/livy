#!/bin/sh

MAPR_HOME=${MAPR_HOME:-/opt/mapr}

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"


# Copy hive-site.xml in Spark and set spark.yarn.dist.files in spark-defaults.conf
if [ -e "${MAPR_HOME}/spark/sparkversion" ]; then
    SPARK_VERSION=$(cat "${MAPR_HOME}/spark/sparkversion")
    SPARK_HOME="${MAPR_HOME}/spark/spark-${SPARK_VERSION}"
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
