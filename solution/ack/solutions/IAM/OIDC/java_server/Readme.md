# Java程序演示在ACK容器里面通过OIDC配置操作云资源
## 启用RRSA功能
本步骤配置容器服务的RRSA功能，若已配置参数可跳过该步骤。
1. 登录容器服务管理控制台。
2. 在控制台左侧导航栏中，单击集群。
3. 在集群列表页面中，单击目标集群名称或者目标集群右侧操作列下的详情。
4. 在集群详情页面，单击基本信息页签，单击RRSA OIDC提供商URL右侧的启用RRSA。
5. 在弹出的启用RRSA对话框中，单击确定。当集群状态由更新中变为运行中时，说明该集群的RRSA特性已变更完成，RRSA OIDC提供商URL右侧会显示OIDC提供商的URL链接。

## 使用RRSA功能
集群开启RRSA功能后，按照以下步骤来赋予集群内应用通过RRSA功能获取访问云资源OpenAPI的临时凭证的能力。
1. 创建RAM角色
   需要为应用所使用的服务账户（Service Account）创建一个RAM角色。后续应用将获取一个扮演这个RAM角色的临时凭证。更多信息，请参见创建可信实体为阿里云账号的RAM角色。
2. 修改RAM角色信任策略。
   您需要修改RAM角色的信任策略，确保使用指定的服务账户的应用有权限获取一个扮演这个RAM角色的临时凭证。更多信息，请参见修改RAM角色的信任策略。
```json
{
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": {
          "oidc:aud": "sts.aliyuncs.com",
          /*<oidc_issuer_url>替换为当前集群的OIDC提供商URL，该URL可以在集群详情的基本信息页签获取。*/
          "oidc:iss": "<oidc_issuer_url>",
          /*<namespace>替换为应用所在的命名空间,本示例为：default。*/
          /*<service_account>替换为应用使用的服务账户,本示例为：app。*/
          "oidc:sub": "system:serviceaccount:<namespace>:<service_account>"
          
        }
      },
      "Effect": "Allow",
      "Principal": {
        "Federated": [
          /*<account_uid>替换为阿里云主账号UID。<cluster_id>替换为ACK集群ID。*/
          "acs:ram::<account_uid>:oidc-provider/ack-rrsa-<cluster_id>"
        ]
      }
    }
  ],
  "Version": "1"
}
```
3. 为RAM角色授权。您可以通过为这个RAM角色授权的方式，指定这个RAM角色可以访问的云资源。更多信息，请参见为RAM角色授权。
4. 部署应用
   部署应用时，需要修改应用的模板内容，自动生成OIDC Token。具体操作，请参见部署服务账户令牌卷投影。
   模板示例：

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: test-rrsa
spec:
  containers:
  - image: alpine:3.14
    command:
    - sh
    - -c
    - 'sleep inf'
    name: test
    volumeMounts:
    - mountPath: /var/run/secrets/tokens
      name: oidc-token
  serviceAccountName: build-robot
  volumes:
  - name: oidc-token     # 新增的配置项。
    projected:
      sources:
      - serviceAccountToken:
          path: oidc-token
          expirationSeconds: 7200    # oidc token过期时间（单位：秒）。
          audience: "sts.aliyuncs.com"
```

## 应用程序使用RRSA OIDC Token认证
1. 创建一个具备调用STS接口的AK/SK
   应用程序第一步就是调用sts服务AssumeRole接口拿到一个临时ak/sk。对应RAM账号自定义授权Policy如下：

## 创建Docker环境
1. 基于本示例中的Dockerfile / Dockerfile_biz 打出基础运行环境及应用运行环境
```shell
docker build -t docker-images-domain/namespace/registery:tag -f Dockerfile ./
```
2. 在ACK集群上启动这个容器
测试的URL：
```shell
curl http://127.0.0.1:7001/ram/test.do
```


## 注意事项 
1. 程序依据OIDC-TOKEN去获取sts token的时候有可能会刚好命中失效，需要做一下重试。
2. 程序调用STS接口有一个Quota上限，需要合理评估.