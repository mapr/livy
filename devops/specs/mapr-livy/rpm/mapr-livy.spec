%undefine __check_files

summary:     Ezmeral Ecosystem Pack: Livy package
license:     Hewlett Packard Enterprise, CopyRight
vendor:      Hewlett Packard Enterprise, <ezmeral_software_support@hpe.com>
name:        mapr-livy
version:     __RELEASE_VERSION__
release:     1
prefix:      /
group:       MapR
buildarch:   noarch
obsoletes:   mapr-hue-livy
requires:    mapr-spark >= 2.0.0, mapr-client, mapr-hadoop-client
AutoReqProv: no

%description
Ezmeral Ecosystem Pack: Livy package
Tag: __RELEASE_BRANCH__
Commit: __GIT_COMMIT__


%clean
echo "NOOP"


%files
__PREFIX__/livy
__PREFIX__/roles


%pretrans
# stop service if running
if [ -e "__PREFIX__/livy/livyversion" ]; then
    DAEMON_CONF="__PREFIX__/conf/daemon.conf"
    MAPR_USER=${MAPR_USER:-$([ -f "$DAEMON_CONF" ] && grep "mapr.daemon.user" "$DAEMON_CONF" | cut -d '=' -f 2)}
    MAPR_USER=${MAPR_USER:-"mapr"}

    if [ -z "${MAPR_TICKETFILE_LOCATION}" ] && [ -e "__PREFIX__/conf/mapruserticket" ]; then
        export MAPR_TICKETFILE_LOCATION="__PREFIX__/conf/mapruserticket"
    fi

    LIVY_VERSION=$(cat "__PREFIX__/livy/livyversion")
    LIVY_HOME="__PREFIX__/livy/livy-${LIVY_VERSION}"

    if sudo -u $MAPR_USER -E "${LIVY_HOME}/bin/livy-server" status &>/dev/null; then
        RESULT=$(sudo -u $MAPR_USER -E "${LIVY_HOME}/bin/livy-server" stop 2>&1)
        STATUS=$?
        if [ $STATUS -ne 0 ] ; then
            echo "$RESULT"
        fi
    fi
fi


%pre
if [ "$1" = "2" ]; then
    OLD_TIMESTAMP=$(rpm --queryformat='%%{VERSION}' -q mapr-livy)
    OLD_VERSION=$(echo "$OLD_TIMESTAMP" | grep -o '^[0-9]*\.[0-9]*\.[0-9]*')

    OLD_TIMESTAMP_FILE="%{_localstatedir}/lib/rpm-state/mapr-livy-old-timestamp"
    OLD_VERSION_FILE="%{_localstatedir}/lib/rpm-state/mapr-livy-old-version"

    STATE_DIR="$(dirname $OLD_TIMESTAMP_FILE)"
    if [ ! -d "$STATE_DIR" ]; then
        mkdir -p "$STATE_DIR"
    fi

    echo "$OLD_TIMESTAMP" > "$OLD_TIMESTAMP_FILE"
    echo "$OLD_VERSION" > "$OLD_VERSION_FILE"

    #
    # Backup of old configuration files
    #
    OLD_DIR="__PREFIX__/livy/livy-${OLD_VERSION}"
    BCK_DIR="__PREFIX__/livy/livy-${OLD_TIMESTAMP}"

    # Workaround for MLIVY-78
    # Can be removed when no users left on MEP-6.3
    if [ -e "$BCK_DIR" ]; then
        BCK_DIR="${BCK_DIR}-2"
    fi

    CONF_SRC_DST="${OLD_DIR}/conf/ ${BCK_DIR}/"
    echo "$CONF_SRC_DST" | while read CONF_SRC CONF_DST; do
        mkdir -p "$CONF_DST"
        if [ -e "$CONF_SRC" ]; then
            cp -r "$CONF_SRC" "$CONF_DST"
        fi
    done
fi


%post
# clean install:
if [ "$1" = "1" ]; then
  touch "__INSTALL_3DIGIT__/conf/.not_configured_yet"
fi


%preun
# Stop service before uninstall.
# (Code in %pretrans is not executed on uninstall.)
if [ -e "__PREFIX__/livy/livyversion" ]; then
    DAEMON_CONF="__PREFIX__/conf/daemon.conf"
    MAPR_USER=${MAPR_USER:-$([ -f "$DAEMON_CONF" ] && grep "mapr.daemon.user" "$DAEMON_CONF" | cut -d '=' -f 2)}
    MAPR_USER=${MAPR_USER:-"mapr"}

    if [ -z "${MAPR_TICKETFILE_LOCATION}" ] && [ -e "__PREFIX__/conf/mapruserticket" ]; then
        export MAPR_TICKETFILE_LOCATION="__PREFIX__/conf/mapruserticket"
    fi

    LIVY_VERSION=$(cat "__PREFIX__/livy/livyversion")
    LIVY_HOME="__PREFIX__/livy/livy-${LIVY_VERSION}"

    if sudo -u $MAPR_USER -E "${LIVY_HOME}/bin/livy-server" status &>/dev/null; then
        RESULT=$(sudo -u $MAPR_USER -E "${LIVY_HOME}/bin/livy-server" stop 2>&1)
        STATUS=$?
        if [ $STATUS -ne 0 ] ; then
            echo "$RESULT"
        fi
    fi
fi


%postun
# uninstall:
if [ "$1" = "0" ]; then
    if [ -f __PREFIX__/conf/conf.d/warden.livy.conf ]; then
        rm -f __PREFIX__/conf/conf.d/warden.livy.conf
    fi

    rm -rf __PREFIX__/livy/
fi


%posttrans
OLD_TIMESTAMP_FILE="%{_localstatedir}/lib/rpm-state/mapr-livy-old-timestamp"
OLD_VERSION_FILE="%{_localstatedir}/lib/rpm-state/mapr-livy-old-version"

# This files will exist only on upgrade
if [ -e "$OLD_TIMESTAMP_FILE" ] && [ -e "$OLD_VERSION_FILE" ]; then 
    OLD_TIMESTAMP=$(cat "$OLD_TIMESTAMP_FILE")
    OLD_VERSION=$(cat "$OLD_VERSION_FILE")

    rm "$OLD_TIMESTAMP_FILE" "$OLD_VERSION_FILE"

    # Remove directory with old version
    NEW_VERSION=$(cat __PREFIX__/livy/livyversion)

    if [ "$OLD_VERSION" != "$NEW_VERSION" ]; then
        rm -rf "__PREFIX__/livy/livy-${OLD_VERSION}"
    fi
fi
