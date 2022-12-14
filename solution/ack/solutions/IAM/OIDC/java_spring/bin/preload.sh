#!/bin/bash
##
## Usage  :    sh bin/jbossctl pubstart
## Author :    baoqing.guobq@alibaba-inc.com, juven.xuxb@alibaba-inc.com
## Docs   :    http://gitlab.alibaba-inc.com/spring-boot/docs/wikis/home
## =========================================================================================

HEALTH_URL="http://localhost:7002/actuator/health"
echo "        -- health check url: ${HEALTH_URL}"
status=0
times=180
PIDFILE="/tmp/.application.pid.bak"

for e in $(seq 180); do
	sleep 1
	COSTTIME=$(($times - $e ))

	if [ -f "$PIDFILE" ]; then
	    APP_PID=`cat $PIDFILE`
	    checkjavapid=`ps aux | grep -w $APP_PID | grep -v grep | awk '{print $2}'`
	    if [ "X$checkjavapid" == "X" ]; then
	        status=0
	        break;
	    fi
	fi

	HEALTH_CHECK_CODE=`curl -s --connect-timeout 3 --max-time 5 ${HEALTH_URL} -o /dev/null -w %{http_code}`
	if [ "$HEALTH_CHECK_CODE" == "200" ]; then
		status=1
		break;
    elif [ "$HEALTH_CHECK_CODE" == "503" ] && curl -s --connect-timeout 3 --max-time 5 ${HEALTH_URL} | grep -q '"status":"DOWN"' ; then
        echo -n -e  "\r        -- check heath lasts `expr $COSTTIME` seconds."
	elif [ "$HEALTH_CHECK_CODE" == "503" ] && curl -s --connect-timeout 3 --max-time 5 ${HEALTH_URL} | grep -q OUT_OF_SERVICE ; then
		status=1
		break;
	else
		echo -n -e  "\r        -- check heath lasts `expr $COSTTIME` seconds."
	fi		
done
echo ""
if [ $status -eq 0 ]; then
    echo "        -- health check failed."
    HEALTH_CHECK_CODE=`curl -s --connect-timeout 3 --max-time 5 ${HEALTH_URL} -o /dev/null -w %{http_code}`
    if [ $? -eq 7 ]; then
        echo "        -- could not connect to ${HEALTH_URL}"
    else
        echo "        -- server responded http code ${HEALTH_CHECK_CODE}"
    fi
    exit 10000
fi
