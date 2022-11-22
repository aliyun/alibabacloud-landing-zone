#!/bin/bash

# 执行脚本：./build.sh v0.1

TAG=$1

mvn clean package -DskipTests
cp target/calcula.jar calcula.jar

# yaofangapp
docker build -t dsafd-registry.cn-shenzhen.cr.aliyuncs.com/yaofangapp/calcula:${TAG} -f Dockerfile_biz ./
docker push dsafd-registry.cn-shenzhen.cr.aliyuncs.com/yaofangapp/calcula:${TAG}

docker images | grep calcula

rm calcula.jar