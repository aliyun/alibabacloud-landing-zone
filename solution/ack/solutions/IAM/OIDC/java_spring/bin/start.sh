#!/bin/bash
#######  error code specification  #########
# Please update this documentation if new error code is added.
# 1   => reserved for script error
# 2   => bad usage
# 3   => bad user
# 4   => tomcat start failed
# 5   => preload.sh check failed
# 6   => APP_NAME empty
# 7   => APP_ENVIRONMENT empty
# 128 => exit with error message

if [ -z ${APP_NAME} ]; then
    echo "APP_NAME is empty"
	exit 6
fi

if [ -z ${APP_ENVIRONMENT} ]; then
	echo "APP_ENVIRONMENT is empty"
	exit 7
fi

if ! [ -d /root/${APP_NAME} ]; then
	mkdir -p /root/${APP_NAME}
fi

## extract_tgz
tar zxvf /root/${APP_NAME}/target/${APP_NAME}.tgz -C /root/${APP_NAME}/target/

/root/${APP_NAME}/bin/appctl.sh restart

echo "app start result code:$?"


# 容器启动不退出
tail -2000f /root/${APP_NAME}/logs/java.log