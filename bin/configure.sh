#!/bin/bash
#######################################################################
# Copyright (c) 2009 & onwards. MapR Tech, Inc., All rights reserved
#######################################################################
#
# Configure script for Hue Livy
#
# This script is normally run by the core configure.sh to setup Livy 
# connect during install. If it is run standalone, need to correctly 
# initialize the variables that it normally inherits from the master
# configure.sh
#######################################################################


RETURN_SUCCESS=0
RETURN_ERR_MAPR_HOME=1
RETURN_ERR_ARGS=2
RETURN_ERR_MAPRCLUSTER=3
RETURN_ERR_OTHER=4


# Initialize API and globals

MAPR_HOME=${MAPR_HOME:-/opt/mapr}

. ${MAPR_HOME}/server/common-ecosystem.sh  2> /dev/null
{ set +x; } 2>/dev/null

initCfgEnv

if [ $? -ne 0 ] ; then
  echo '[ERROR] MAPR_HOME seems to not be set correctly or mapr-core not installed.'
  exit $RETURN_ERR_MAPR_HOME
fi

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"

WARDEN_LIVY_DEST_CONF="$MAPR_HOME/conf/conf.d/warden.livy.conf"
WARDEN_LIVY_FILE="$LIVY_HOME/conf/warden.livy.conf"

DAEMON_CONF=${MAPR_HOME}/conf/daemon.conf

MAPR_CONF_DIR=${MAPR_CONF_DIR:-"$MAPR_HOME/conf"}

LIVY_CONF_FILES=(
  "${LIVY_HOME}/conf/livy-client.conf"
  "${LIVY_HOME}/conf/livy.conf"
  "${LIVY_HOME}/conf/livy-env.sh"
  "${LIVY_HOME}/conf/log4j.properties"
  "${LIVY_HOME}/conf/spark-blacklist.conf"
)
LIVY_CONF_TEMPLATES=(
  "${LIVY_HOME}/conf/livy-client.conf.template"
  "${LIVY_HOME}/conf/livy.conf.template"
  "${LIVY_HOME}/conf/livy-env.sh.template"
  "${LIVY_HOME}/conf/log4j.properties.template"
  "${LIVY_HOME}/conf/spark-blacklist.conf.template"
)


# Initialize arguments
isOnlyRoles=${isOnlyRoles:-0}
isSecure=0
customSecure=0


change_permissions() {
  if [ -f $DAEMON_CONF ]; then
    MAPR_USER=$( awk -F = '$1 == "mapr.daemon.user" { print $2 }' $DAEMON_CONF)
    MAPR_GROUP=$( awk -F = '$1 == "mapr.daemon.group" { print $2 }' $DAEMON_CONF)

    if [ ! -z "$MAPR_USER" ]; then
      chown -R ${MAPR_USER} ${LIVY_HOME}
      chown ${MAPR_USER} ${MAPR_CONF_DIR}
    fi

    if [ ! -z "$MAPR_GROUP" ]; then
      chgrp -R ${MAPR_GROUP} ${LIVY_HOME}
      chgrp ${MAPR_GROUP} ${MAPR_CONF_DIR}
    fi
    chmod -f u+x ${LIVY_HOME}/bin/*
  fi
}

setup_warden_conf() {
  if [ -f $WARDEN_LIVY_DEST_CONF ]; then
    rm -f $WARDEN_LIVY_DEST_CONF
  fi
  cp $WARDEN_LIVY_FILE $WARDEN_LIVY_DEST_CONF
  chown ${MAPR_USER} ${WARDEN_LIVY_DEST_CONF}
  chgrp ${MAPR_GROUP} ${WARDEN_LIVY_DEST_CONF}
}

check_port(){
  logInfo "Checking that port $1 is available"
  PORT="$1"
  if checkNetworkPortAvailability "$PORT" 2>/dev/null; then  
    { set +x; } 2>/dev/null
    logInfo "Port $PORT is available"
  else
    { set +x; } 2>/dev/null
    logErr -both "Port $PORT is busy"
  fi
}

create_restart_livy(){
  mkdir -p ${MAPR_CONF_DIR}/restart
  cat > "${MAPR_CONF_DIR}/restart/livy-0.3.0.restart" <<'EOF'
#!/bin/bash
MAPR_USER=${MAPR_USER:-mapr}
sudo -u ${MAPR_USER} maprcli node services -action restart -name livy -nodes $(hostname)
EOF
  chmod +x "${MAPR_CONF_DIR}/restart/livy-0.3.0.restart"
  chown -R $MAPR_USER:$MAPR_GROUP "${MAPR_CONF_DIR}/restart/livy-0.3.0.restart"
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


# Parse options
USAGE="usage: $0 [-h] [-R] [--secure|--unsecure|--customSecure] [-EC <options>]"

while [ ${#} -gt 0 ]; do
  case "$1" in
    --secure)
      isSecure=1;
      logWarn -both "Livy does not support security!"
      shift 1;;
    --unsecure)
      isSecure=0;
      shift 1;;
    --customSecure)
      isSecure=0;
      customSecure=1;
      shift 1;;
    -R)
      isOnlyRoles=1;
      shift 1;;
    -EC)
      for i in $2 ; do
        case $i in
          -R) isOnlyRoles=1 ;;
          *) : ;; # unused in Livy
        esac
      done
      shift 2;;
    -h)
      echo "${USAGE}"
      exit $RETURN_SUCCESS
      ;;
    *)
      # Invalid arguments passed
      echo "${USAGE}"
      exit $RETURN_ERR_ARGS
  esac
done


livy_init_confs
change_permissions
setup_warden_conf
create_restart_livy

exit $RETURN_SUCCESS
