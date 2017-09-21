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


LIVY_HOME="/opt/mapr/livy/livy-0.3.0/"
WARDEN_LIVY_DEST_CONF="$MAPR_HOME/conf/conf.d/warden.livy.conf"
WARDEN_LIVY_FILE="$LIVY_HOME/conf.new/warden.livy.conf"
LIVY_VERSION_FILE="$MAPR_HOME/livy/livyversion"
DAEMON_CONF=${MAPR_HOME}/conf/daemon.conf
VERSION=0.3.0
MAPR_CONF_DIR=${MAPR_CONF_DIR:-"$MAPR_HOME/conf"}


function write_version_file() {
    if [ -f $LIVY_VERSION_FILE ]; then
        rm -f $LIVY_VERSION_FILE
    fi
    echo $VERSION > $LIVY_VERSION_FILE
    chown -R $MAPR_USER:$MAPR_GROUP $LIVY_VERSION_FILE
}

function change_permissions() {
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

function setup_warden_config() {
    if [ -f $WARDEN_LIVY_DEST_CONF ]; then
        rm -f $WARDEN_LIVY_DEST_CONF
    fi
    cp $WARDEN_LIVY_FILE $WARDEN_LIVY_DEST_CONF
    chown ${MAPR_USER} ${WARDEN_LIVY_DEST_CONF}
    chgrp ${MAPR_GROUP} ${WARDEN_LIVY_DEST_CONF}
}

#
# Check that port is available
#
function check_port(){
    logInfo "Checking that port $1 is available"
    PORT="$1"
    if checkNetworkPortAvailability "$PORT" 2>/dev/null; then  
         { set +x; } 2>/dev/null
         logInfo "Port $PORT is available"
    else
         { set +x; } 2>/dev/null
         logError "Port $PORT is busy"
    fi
}
 
function create_restart_livy(){
    mkdir -p ${MAPR_CONF_DIR}/restart
    echo "maprcli node services -action restart -name livy -nodes $(hostname)" > "${MAPR_CONF_DIR}/restart/livy-0.3.0.restart"
    chown -R $MAPR_USER:$MAPR_GROUP "${MAPR_CONF_DIR}/restart/livy-0.3.0.restart"
}

# Parse options

USAGE="usage: $0 [-h] [-R]"


{ OPTS=`getopt -n "$0" -a -o suhR --long secure,unsecure,customSecure,help,EC -- "$@"`; } 2>/dev/null
eval set -- "$OPTS"

for i ; do
  case "$i" in
    --secure)
      isSecure=1;
      shift 1;;
    --unsecure)
      isSecure=0;
      shift 1;;
     --customSecure)
      shift 1;;
    --R)
      isOnlyRoles=1;
      shift 1;;
    --EC)
      ecosystemParams="$2"
      shift 2;;
    --h)
      echo "${USAGE}"
      exit $RETURN_SUCCESS
      ;;
    --)
      shift;;
    *)
      break;;
  esac
done


change_permissions
write_version_file
setup_warden_config
create_restart_livy

exit $RETURN_SUCCESS
