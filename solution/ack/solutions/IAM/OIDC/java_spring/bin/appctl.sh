#!/bin/bash
##
## Usage  :    sh bin/jbossctl pubstart
## Author :    baoqing.guobq@alibaba-inc.com, juven.xuxb@alibaba-inc.com
## Docs   :    http://gitlab.alibaba-inc.com/spring-boot/docs/wikis/home
## =========================================================================================

#######  error code specification  #########
# Please update this documentation if new error code is added.
# 1   => reserved for script error
# 2   => bad usage
# 3   => bad user
# 4   => tomcat start failed
# 5   => preload.sh check failed

# 128 => exit with error message

PROG_NAME=$0
ACTION=$1

usage() {
    echo "Usage: $PROG_NAME {start|stop|online|offline|pubstart|restart|deploy}"
    exit 2 # bad usage
}

if [ $# -lt 1 ]; then
    usage
    exit 2 # bad usage
fi

APP_HOME=$(cd $(dirname $0)/..; pwd)
source "$APP_HOME/bin/setenv.sh"

PACKAGING="unknown"
RUNNABLE_PACKAGE="unknown"
EXPLODED_TARGET="${APP_HOME}/target/"
PIDFILE="${APP_HOME}/logs/application.pid"
PIDFILEBAK="/tmp/.application.pid.bak"
JAVA_OUT="${APP_HOME}/logs/java.log"

startjava() {
    echo "[ 2/3] -- start java process"

    if ! [ -d ${APP_HOME}/logs/ ]; then
        mkdir -p ${APP_HOME}/logs/
    fi

    if ! [ -d ${MIDDLEWARE_LOGS} ]; then
        mkdir -p ${MIDDLEWARE_LOGS}
    fi

    touch ${MIDDLEWARE_LOGS}/gc.log
    touch ${APP_HOME}/logs/application.log

    ## prepare log directory done

    cd ${EXPLODED_TARGET} || exit 1
    echo "        -- java stdout log: ${JAVA_OUT}"

    SPRINGBOOT_OPTS="${SPRINGBOOT_OPTS} --startup.at=$(($(date +%s%N)/1000000))"

    nohup $JAVA_HOME/bin/java -jar $JAVA_OPTS ${APP_NAME}.jar $SPRINGBOOT_OPTS &>$JAVA_OUT &

    for e in $(seq 10); do
        sleep 1
        if [ -f "$PIDFILE" ]; then
            pid=`cat $PIDFILE`
            if [[ "X$pid" != "X" ]]; then
                echo "        -- backup APP_PID=$pid"
                if [ ! -f "$PIDFILEBAK" ]; then
                   touch $PIDFILEBAK
                fi
                echo $pid > $PIDFILEBAK
                break
            fi
        fi
    done

    echo "[ 3/3] -- check health for java application"
    . "$APP_HOME/bin/preload.sh"
    [[ $? -ne 0 ]] && ( echo "check heath failed, exit" && exit 1 )
}

stopjava() {
    echo "[ 1/3] -- stop java process"
    if [ -f "$PIDFILE" ]; then
        rm -rf $PIDFILE
    fi
    if [ -f "$PIDFILEBAK" ]; then
        rm -rf $PIDFILEBAK
    fi
    times=60
    for e in $(seq 60)
    do
        sleep 1
        COSTTIME=$(($times - $e ))
        checkjavapida=`ps -ef|grep java|grep $APP_NAME|grep -v appctl.sh|grep -v jbossctl| grep -v restart.sh |grep -v grep`
        if [[ $checkjavapida ]];then
                checkjavapid=`ps -ef|grep java|grep $APP_NAME|grep -v appctl.sh|grep -v jbossctl | grep -v restart.sh |grep -v grep|awk '{print $2}'`
                kill -9 $checkjavapid
                echo -n -e  "\r        -- stopping java lasts `expr $COSTTIME` seconds."
        else
                break;
        fi
    done
    echo ""
}

backup() {
    echo "backup..."
}


case "$ACTION" in
    start)
        startjava
    ;;
    stop)

        stopjava
    ;;
    pubstart)
        stopjava
        startjava
    ;;
    online)
    ;;
    offline)

    ;;
    restart)
        stopjava
        startjava
    ;;
    deploy)
        stopjava
        startjava
        backup
    ;;
    *)
        usage
    ;;
esac
