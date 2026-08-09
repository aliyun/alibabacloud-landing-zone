# AutoWonder S3 对象存储使用与配置说明

- 版本:v1.0
- 日期:2026-08-09
- 适用范围:标准 S3 协议对象存储(AWS S3 / MinIO / 其它 S3 兼容自建服务)。
- 定位:S3 是与阿里云 OSS **并列的可插拔对象存储后端**。除配置外,S3 为**完全独立的模块**,不侵入现有 OSS 代码链路;二者**互斥**,同一时刻只启用一个。

---

## 1. 何时使用 S3

| 场景 | 推荐后端 |
|---|---|
| 阿里云内网部署,已有 OSS bucket | `oss`(默认) |
| 私有化 / 本地 / 非阿里云环境 | `s3`(对接自建 MinIO 或云厂商 S3) |
| 本地开发联调对象存储链路 | `s3` + 本地 MinIO |

> Community 应用默认仍走 OSS。阿里云部署 Skill 只交付 OSS 架构；S3
> 适用于自行管理的非阿里云部署，切换时无需改代码。

---

## 2. 架构与隔离约定

- **抽象层**:所有业务代码只依赖 `ObjectStorage` 接口(`put/get/presignGet/exists/delete`),不感知具体后端。
- **可插拔装配**(`storage/ObjectStorageConfig.java`):
  - `oss.enabled=true` → 装配 `AliyunOssObjectStorage`;
  - `s3.enabled=true` → 装配 `S3ObjectStorage`;
  - 两者都未启用 → 回退 `InMemoryObjectStorage`(仅测试/占位)。
- **互斥保护**:`oss.enabled` 与 `s3.enabled` 同时为 `true` 时,启动即抛 `IllegalStateException` 快速失败。
- **模块独立**:`S3ObjectStorage` / `S3Properties` 为新增独立类;OSS 相关类零改动,已运行的 OSS 链路不受影响。
- **双客户端拆分**(与 OSS 实现一致):
  - service client 使用**内网 `endpoint`** 做 put/get/head/delete;
  - presigner 使用**外部可达 `public-endpoint`** 签发下载 URL,保证 VPC 外客户端能解析签名地址。

---

## 3. 配置项参考(`s3.*`)

| 配置键 | 说明 | 默认值 |
|---|---|---|
| `s3.enabled` | 是否启用 S3 后端(与 `oss.enabled` 互斥) | `false` |
| `s3.endpoint` | 服务端读写用的内网 endpoint | 无(启用时必填) |
| `s3.public-endpoint` | 签发下载 URL 用的外部可达 endpoint;留空则回退用 `endpoint` | 空 |
| `s3.region` | SigV4 region;MinIO 用占位值即可 | `us-east-1` |
| `s3.access-key-id` | Access Key | 无(启用时必填) |
| `s3.access-key-secret` | Secret Key | 无(启用时必填) |
| `s3.force-path-style` | 路径风格寻址;MinIO/多数自建服务需为 `true` | `true` |

> **桶名不在 `s3.*` 下配置。** 桶名与后端无关,统一复用 `oss.*`:`oss.task-pkg-bucket`(任务包)、`oss.artifact-bucket`(产物)。启用 S3 时这些 `oss.*` 桶名依然生效,由 `TaskPackager`(经 `ObjectStorageConfig`)与产物服务消费。`oss.skill-bucket` 为可选的 skill 物理隔离桶,留空时回退到 `oss.artifact-bucket`。

### 启动校验(fail-fast)

`s3.enabled=true` 时,`S3Properties.validate()` 会校验 `endpoint / region / access-key-id / access-key-secret` 均非空,缺失则启动失败。

---

## 4. 配置示例

### 4.1 环境变量（默认关闭）

```bash
OSS_ENABLED=false
S3_ENABLED=true
S3_ENDPOINT=http://minio.internal:9000
S3_PUBLIC_ENDPOINT=https://s3.example.com
S3_REGION=us-east-1
S3_ACCESS_KEY_ID=xxxx
S3_ACCESS_KEY_SECRET=yyyy
S3_FORCE_PATH_STYLE=true
```

### 4.2 本地 MinIO 联调(`application-local.yml`)

```yaml
oss:
  enabled: false            # 关闭 OSS,避免与 s3 互斥冲突
  task-pkg-bucket: autowonder-pkg
  artifact-bucket: autowonder-artifact
  skill-bucket: ""

s3:
  enabled: true             # endpoint/keys 继承 application.yml 的 s3 块或环境变量
```

> Community 基础配置默认把 `oss.enabled` 设为 `true`。启用 S3 时必须将
> `OSS_ENABLED=false` 与 `S3_ENABLED=true` 同时写入运行环境，否则启动会因
> 两个后端同时启用而快速失败。

### 4.3 环境变量注入(推荐,不落明文)

```bash
export S3_ENDPOINT=http://minio.internal:9000
export S3_PUBLIC_ENDPOINT=https://s3.example.com
export S3_REGION=us-east-1
export S3_ACCESS_KEY_ID=xxxx
export S3_ACCESS_KEY_SECRET=yyyy
```

---

## 5. Bucket 准备

S3 后端复用 `oss.*` 的 bucket 配置，最小集合为:

- **task-package**:调度任务包 zip(服务端写 / 执行器读)。
- **artifact**:执行产物、用户上传 skill zip(执行器写 / 服务端+前端读)。

在 MinIO 上按 `oss.task-pkg-bucket` / `oss.artifact-bucket` 配置的名字**预先创建同名 bucket**即可(如 `autowonder-pkg`、`autowonder-artifact`)。对象 Key 布局继续使用租户前缀 `t/{tenantId}/...`。

---

## 6. MinIO / S3 兼容性说明

`S3ObjectStorage` 已针对自建 S3 服务做如下适配(`storage/S3ObjectStorage.java`):

- **路径风格寻址**:`force-path-style=true`,避免 virtual-hosted 风格域名解析失败。
- **校验和策略**:`RequestChecksumCalculation.WHEN_REQUIRED` + `ResponseChecksumValidation.WHEN_REQUIRED`,兼容早于 AWS SDK v2 2.30.0(默认由 MD5 改为 CRC32)之前的 MinIO 版本,规避 `x-amz-checksum` 不识别导致的上传失败。
- **HTTP 客户端**:使用 `UrlConnectionHttpClient`(轻量,无 Netty/Apache5 传递依赖)。
- **依赖**:AWS SDK for Java **v2**(`software.amazon.awssdk:s3`,版本见 `pom.xml` `aws-sdk-version`),已排除 `apache5-client`、`netty-nio-client`,`S3Presigner` 随 `s3` 构件提供。

---

## 7. 本地联调步骤(MinIO)

1. 启动 MinIO(API `:9000`,Console `:9001`),创建 bucket `autowonder-pkg`、`autowonder-artift`。
2. 按 §4.2 配置 `application-local.yml`;endpoint/AK/SK 走环境变量。
3. 以 `local` profile 启动(端口 7001)。
4. 验证:触发一次任务打包/产物上传链路,确认对象写入 MinIO,且下载签名 URL 可访问。

---

## 8. 常见问题(Troubleshooting)

| 现象 | 原因 / 处理 |
|---|---|
| 启动报 `oss.enabled and s3.enabled are mutually exclusive` | 两个后端同时启用;检查生效 profile(尤其 `daily`/`local`)里的 `oss.enabled`,确保只留一个为 `true`。 |
| 启动报 `s3.endpoint/region/access-key-id/... is required` | `s3.enabled=true` 但必填项为空;补齐 endpoint 与 AK/SK。 |
| 上传报 checksum / `x-amz-checksum` 相关错误 | 老版本 MinIO;升级 MinIO,或确认已使用本模块(已设 `WHEN_REQUIRED`)。 |
| 下载签名 URL 在 VPC 外无法访问 | `public-endpoint` 配成了内网地址;改为外部可达域名。 |
| bucket not found | 未在 MinIO 预建与 `oss.*` 桶名一致的 bucket。 |

---

## 9. 安全约定

- 生产环境 AK/SK **不落明文**,统一走环境变量或外部密钥管理注入；`application-local.yml` 中不得提交真实凭据。
- 下载统一走**签名 URL + TTL**,不暴露长期 AK。
- 与 OSS 一致遵循租户 Key 前缀隔离(`t/{tenantId}/`)。
