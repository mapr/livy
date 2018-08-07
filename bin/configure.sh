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


# Initialize API and globals

MAPR_HOME=${MAPR_HOME:-/opt/mapr}

. ${MAPR_HOME}/server/common-ecosystem.sh 2>/dev/null
{ set +x; } 2>/dev/null

initCfgEnv

if [ $? -ne 0 ] ; then
  echo '[ERROR] MAPR_HOME seems to not be set correctly or mapr-core not installed.'
  exit $RETURN_ERR_MAPR_HOME
fi

MAPR_CONF_DIR=${MAPR_CONF_DIR:-"${MAPR_HOME}/conf"}

LIVY_VERSION=$(cat "${MAPR_HOME}/livy/livyversion")
LIVY_HOME="${MAPR_HOME}/livy/livy-${LIVY_VERSION}"

WARDEN_LIVY_SRC="${LIVY_HOME}/conf/warden.livy.conf"
WARDEN_LIVY_CONF="${MAPR_CONF_DIR}/conf.d/warden.livy.conf"

WARDEN_HEAPSIZE_MIN_KEY="service.heapsize.min"
WARDEN_HEAPSIZE_MAX_KEY="service.heapsize.max"
WARDEN_HEAPSIZE_PERCENT_KEY="service.heapsize.percent"
WARDEN_RUNSTATE_KEY="service.runstate"


LIVY_CONF_TUPLES="${LIVY_HOME}/conf/livy-client.conf ${LIVY_HOME}/conf/livy-client.conf.template
${LIVY_HOME}/conf/livy.conf ${LIVY_HOME}/conf/livy.conf.template
${LIVY_HOME}/conf/livy-env.sh ${LIVY_HOME}/conf/livy-env.sh.template
${LIVY_HOME}/conf/log4j.properties ${LIVY_HOME}/conf/log4j.properties.template
${LIVY_HOME}/conf/spark-blacklist.conf ${LIVY_HOME}/conf/spark-blacklist.conf.template"

LIVY_FILE_SECURE="${LIVY_HOME}/conf/.isSecure"
LIVY_FILE_NOT_CONFIGURED="${LIVY_HOME}/conf/.not_configured_yet"


# Set MAPR_USER and MAPR_GROUP
DAEMON_CONF="${MAPR_HOME}/conf/daemon.conf"

MAPR_USER=${MAPR_USER:-$([ -f "$DAEMON_CONF" ] && grep "mapr.daemon.user" "$DAEMON_CONF" | cut -d '=' -f 2)}
MAPR_USER=${MAPR_USER:-"mapr"}

MAPR_GROUP=${MAPR_GROUP:-$([ -f "$DAEMON_CONF" ] && grep "mapr.daemon.group" "$DAEMON_CONF" | cut -d '=' -f 2)}
MAPR_GROUP=${MAPR_GROUP:-$MAPR_USER}


read_secure() {
  [ -e "$LIVY_FILE_SECURE" ] && cat "$LIVY_FILE_SECURE"
}

write_secure() {
  echo "$1" > "$LIVY_FILE_SECURE"
}

chown_component() {
  chown -R $MAPR_USER:$MAPR_GROUP "$LIVY_HOME"
}

create_restart_file() {
  mkdir -p "${MAPR_CONF_DIR}/restart"
  cat > "${MAPR_CONF_DIR}/restart/livy-${LIVY_VERSION}.restart" <<'EOF'
#!/bin/bash
MAPR_HOME=${MAPR_HOME:-/opt/mapr}
MAPR_USER=${MAPR_USER:-mapr}
if [ -z "$MAPR_TICKETFILE_LOCATION" ] && [ -e "${MAPR_HOME}/conf/mapruserticket" ]; then
    export MAPR_TICKETFILE_LOCATION="${MAPR_HOME}/conf/mapruserticket"
fi
sudo -u $MAPR_USER -E maprcli node services -action restart -name livy -nodes $(hostname)
EOF
  chmod +x "${MAPR_CONF_DIR}/restart/livy-${LIVY_VERSION}.restart"
  chown $MAPR_USER:$MAPR_GROUP "${MAPR_CONF_DIR}/restart/livy-${LIVY_VERSION}.restart"
}

init_livy_confs() {
  echo "$LIVY_CONF_TUPLES" | while read livy_conf_file livy_conf_template; do
    if [ ! -e "$livy_conf_file" ]; then
      cp "$livy_conf_template" "$livy_conf_file"
    fi
  done
}

conf_uncomment() {
  local conf_file="$1"
  local property_name="$2"
  local delim="="
  sed -i "s|#*\s*${property_name}\s*${delim}|${property_name} ${delim}|" "${conf_file}"
}

conf_comment() {
  local conf_file="$1"
  local property_name="$2"
  local delim="="
  sed -i "s|#*\s*${property_name}\s*${delim}|# ${property_name} ${delim}|" "${conf_file}"
}

conf_get_property() {
  local conf_file="$1"
  local property_name="$2"
  local delim="="
  grep "^\s*${property_name}" "${conf_file}" | sed "s|^\s*${property_name}\s*${delim}\s*||"
}

conf_set_property() {
  local conf_file="$1"
  local property_name="$2"
  local property_value="$3"
  local delim="="
  if grep -q "^\s*${property_name}\s*${delim}" "${conf_file}"; then
    # modify property
    sed -i -r "s|^\s*${property_name}\s*${delim}.*$|${property_name}${delim}${property_value}|" "${conf_file}"
  else
    echo "${property_name}${delim}${property_value}" >> "${conf_file}"
  fi
}

perm_scripts() {
  chmod 0700 "${LIVY_HOME}/bin/configure.sh"
}

configure_superusers() {
  conf_uncomment "${LIVY_HOME}/conf/livy.conf" "livy.superusers"
  conf_set_property "${LIVY_HOME}/conf/livy.conf" "livy.superusers" "$MAPR_USER"
}

configure_secure() {
  conf_uncomment "${LIVY_HOME}/conf/livy.conf" "livy.server.auth.type"
  conf_set_property "${LIVY_HOME}/conf/livy.conf" "livy.server.auth.type" "multiauth"
}

configure_unsecure() {
  conf_uncomment "${LIVY_HOME}/conf/livy.conf" "livy.server.auth.type"
  conf_set_property "${LIVY_HOME}/conf/livy.conf" "livy.server.auth.type" ""
  conf_comment "${LIVY_HOME}/conf/livy.conf" "livy.server.auth.type"
}

configure_hive() {
  if [ -e "${MAPR_HOME}/hive/hiveversion" ]; then
    conf_uncomment "${LIVY_HOME}/conf/livy.conf" "livy.repl.enable-hive-context"
    conf_set_property "${LIVY_HOME}/conf/livy.conf" "livy.repl.enable-hive-context" "true"
  else
    conf_uncomment "${LIVY_HOME}/conf/livy.conf" "livy.repl.enable-hive-context"
    conf_set_property "${LIVY_HOME}/conf/livy.conf" "livy.repl.enable-hive-context" ""
    conf_comment "${LIVY_HOME}/conf/livy.conf" "livy.repl.enable-hive-context"
  fi
}

setup_warden_conf() {
  local curr_heapsize_min
  local curr_heapsize_max
  local curr_heapsize_percent
  local curr_runstate

  if [ -f "$WARDEN_LIVY_CONF" ]; then
    curr_heapsize_min=$(conf_get_property "$WARDEN_LIVY_CONF" "$WARDEN_HEAPSIZE_MIN_KEY")
    curr_heapsize_max=$(conf_get_property "$WARDEN_LIVY_CONF" "$WARDEN_HEAPSIZE_MAX_KEY")
    curr_heapsize_percent=$(conf_get_property "$WARDEN_LIVY_CONF" "$WARDEN_HEAPSIZE_PERCENT_KEY")
    curr_runstate=$(conf_get_property "$WARDEN_LIVY_CONF" "$WARDEN_RUNSTATE_KEY")
  fi

  cp "$WARDEN_LIVY_SRC" "$WARDEN_LIVY_CONF"

  [ -n "$curr_heapsize_min" ] && conf_set_property "$WARDEN_LIVY_CONF" "$WARDEN_HEAPSIZE_MIN_KEY" "$curr_heapsize_min"
  [ -n "$curr_heapsize_max" ] && conf_set_property "$WARDEN_LIVY_CONF" "$WARDEN_HEAPSIZE_MAX_KEY" "$curr_heapsize_max"
  [ -n "$curr_heapsize_percent" ] && conf_set_property "$WARDEN_LIVY_CONF" "$WARDEN_HEAPSIZE_PERCENT_KEY" "$curr_heapsize_percent"
  [ -n "$curr_runstate" ] && conf_set_property "$WARDEN_LIVY_CONF" "$WARDEN_RUNSTATE_KEY" "$curr_runstate"

  chown $MAPR_USER:$MAPR_GROUP "$WARDEN_LIVY_CONF"
}


# Initialize arguments
isOnlyRoles=${isOnlyRoles:-0}

# Parse options
USAGE="usage: $0 [-h] [-R] [--secure|--unsecure|--customSecure] [-EC <options>]"

while [ ${#} -gt 0 ]; do
  case "$1" in
    --secure)
      isSecure="true";
      shift 1;;
    --unsecure)
      isSecure="false";
      shift 1;;
    --customSecure)
      isSecure="custom";
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


if [ "$isOnlyRoles" = "1" ]; then
  oldSecure=$(read_secure)
  updSecure="false"
  if [ -n "$isSecure" ] && [ "$isSecure" != "$oldSecure" ]; then
    updSecure="true"
  fi

  init_livy_confs

  configure_superusers

  configure_hive

  if [ "$updSecure" = "true" ]; then
    write_secure "$isSecure"

    if [ "$isSecure" = "true" ]; then
      configure_secure
    elif [ "$isSecure" = "false" ]; then
      configure_unsecure
    fi
  fi

  perm_scripts

  chown_component

  setup_warden_conf

  if [ -f "$LIVY_FILE_NOT_CONFIGURED" ]; then
    rm -f "$LIVY_FILE_NOT_CONFIGURED"
  elif [ "$updSecure" = "true" ]; then
    create_restart_file
  fi
fi
