FROM alpine

RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && \
apk add curl && \
apk add python3 && \
curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py && \
python3 get-pip.py

