FROM alibaba-cloud-linux-3-registry.cn-hangzhou.cr.aliyuncs.com/alinux3/alinux3

RUN yum -y install python3.8 && \
yum -y install java && \
yum -y install tar  && \
curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py && \
python3 get-pip.py  && \
pip3 install -i https://pypi.tuna.tsinghua.edu.cn/simple alibabacloud_sts20150401==1.1.1 && \
pip3 install -i https://pypi.tuna.tsinghua.edu.cn/simple aliyun-python-sdk-sts==3.1.0
