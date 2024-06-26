# 通过KMS实现固定AccessKey的集中管控

该代码示例用于演示，在 AccessKey 托管在 KMS 中后，如何在代码程序中获取并使用。入口文件为 `Main.java`，本地和云上使用，相应的配置会有所不同，这里会通过不同的 profile 来区分：

- 本地：src/main/resources/managed_credentials_providers.properties
- 云上：src/config/prod-aliyun/resources/managed_credentials_providers.properties

## 本地运行

1. 将 KMS 实例接入点的 clientkey 文件保存到 `src/main/resources` 下，重命名为 `clientKey_Password.txt` 和 `clientKey.json`，或者您也可以直接修改配置文件 `src/main/resources/managed_credentials_providers.properties`，指定具体的 clientkey 文件路径，详细配置方式请参考[RAM凭据插件](https://help.aliyun.com/zh/kms/developer-reference/ram-secret-plug-in)中`使用应用接入点（AAP）的Client Key，通过KMS服务Endpoint获取凭据值。`章节
2. 配置完后，本地直接运行入口文件即可：`Main.java`

## 云上运行

1. 通过命令：`mvn -U clean package -Pprod-aliyun` 打成 jar 包
2. 将 target 目录下的 `kms-ram-secrets-jar-with-dependencies.jar` 文件上传到 ECS 或 ACK 上
3. 将 KMS 实例接入点的 clientkey 文件和 KMS 实例 CA 证书上传保存到 ECS 或 ACK `/root/resources` 下，重命名为 `clientKey_Password.txt`、`clientKey.json` 和 `PrivateKmsCA.pem`，或者您也可以直接修改配置文件 `src/config/prod-aliyun/resources/managed_credentials_providers.properties`，指定具体的文件路径，详细配置方式请参考[RAM凭据插件](https://help.aliyun.com/zh/kms/developer-reference/ram-secret-plug-in)中`（推荐）使用应用接入点（AAP）的Client Key，通过KMS实例Endpoint获取凭据值。`章节
4. 在机器上执行命令 `java -jar kms-ram-secrets-jar-with-dependencies.jar`
