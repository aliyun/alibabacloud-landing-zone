#!/bin/bash

# os env
# NOTE: must edit LANG and JAVA_FILE_ENCODING together
export LANG=zh_CN.GB18030
export JAVA_FILE_ENCODING=GB18030

export JAVA_HOME=/opt/xpmeng/java
export CPU_COUNT="$(grep -c 'cpu[0-9][0-9]*' /proc/stat)"
echo "setenv.sh-INFO:CPU_COUNT:${CPU_COUNT}"
ulimit -c unlimited

MIDDLEWARE_LOGS="${APP_HOME}/logs/java"

# env check and calculate
#
if [ -z "$APP_NAME" ]; then
	APP_NAME=$(basename "${APP_HOME}")
fi

JAVA_OPTS="-server"

# use memory based on the available resources in the machine
let memTotal=`cat /proc/meminfo | grep MemTotal | awk '{printf "%d", $2/1024*0.75 }'`

echo "setenv.sh-INFO:memTotal:${memTotal}"

if [ $memTotal -gt 6000 ]; then
    JAVA_OPTS="${JAVA_OPTS} -Xms4g -Xmx4g"
    JAVA_OPTS="${JAVA_OPTS} -Xmn2g"
else
    JAVA_OPTS="${JAVA_OPTS} -Xms2g -Xmx2g"
    JAVA_OPTS="${JAVA_OPTS} -Xmn1g"
fi
JAVA_OPTS="${JAVA_OPTS} -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=256m"
JAVA_OPTS="${JAVA_OPTS} -XX:MaxDirectMemorySize=1g"
JAVA_OPTS="${JAVA_OPTS} -XX:SurvivorRatio=10"
JAVA_OPTS="${JAVA_OPTS} -XX:+UseConcMarkSweepGC -XX:CMSMaxAbortablePrecleanTime=5000"
JAVA_OPTS="${JAVA_OPTS} -XX:+CMSClassUnloadingEnabled -XX:CMSInitiatingOccupancyFraction=80 -XX:+UseCMSInitiatingOccupancyOnly"
JAVA_OPTS="${JAVA_OPTS} -XX:+ExplicitGCInvokesConcurrent -Dsun.rmi.dgc.server.gcInterval=2592000000 -Dsun.rmi.dgc.client.gcInterval=2592000000"
JAVA_OPTS="${JAVA_OPTS} -XX:ParallelGCThreads=${CPU_COUNT}"
JAVA_OPTS="${JAVA_OPTS} -Xloggc:${MIDDLEWARE_LOGS}/gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps"
JAVA_OPTS="${JAVA_OPTS} -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${MIDDLEWARE_LOGS}/java.hprof"
JAVA_OPTS="${JAVA_OPTS} -Djava.awt.headless=true"
JAVA_OPTS="${JAVA_OPTS} -Dsun.net.client.defaultConnectTimeout=10000"
JAVA_OPTS="${JAVA_OPTS} -Dsun.net.client.defaultReadTimeout=30000"
JAVA_OPTS="${JAVA_OPTS} -Dfile.encoding=UTF-8"
JAVA_OPTS="${JAVA_OPTS} -Dproject.name=${APP_NAME}"

SPRINGBOOT_OPTS="--server.port=7001 --management.server.port=7002"
SPRINGBOOT_OPTS="${SPRINGBOOT_OPTS} --spring.profiles.active=${APP_ENVIRONMENT}"
SPRINGBOOT_OPTS="${SPRINGBOOT_OPTS} --logging.path=${APP_HOME}/logs --logging.file=${APP_HOME}/logs/application.log"
SPRINGBOOT_OPTS="${SPRINGBOOT_OPTS} --spring.pid.file=/root/${APP_NAME}/logs/application.pid"


export JAVA_OPTS
export SPRINGBOOT_OPTS
