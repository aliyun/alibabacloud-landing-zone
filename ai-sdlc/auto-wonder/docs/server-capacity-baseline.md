# 单机连接与并发配置基线

目标：为百人并发、5000 条连接提供基础容量配置，同时避免小规模部署常驻大量线程和连接。此目标不是压测结论，5000 条空闲 WebSocket 与5000个持续执行任务的执行器负载不同。

所有内置环境继承 `application.yml` 的容量配置；环境文件仅保留地址、凭证、端口和原有业务差异。外部部署配置仍可覆盖这些值，发布后应核对最终生效配置。

| 参数 | 默认值 | 环境变量 | 用途 |
| --- | ---: | --- | --- |
| Tomcat 最大连接 | 8192 | AW_HTTP_MAX_CONNECTIONS | 为5000条连接留出HTTP、重连余量；不是线程数 |
| Tomcat 工作线程最大/最低 | 400 / 10 | AW_HTTP_THREADS_MAX / AW_HTTP_THREADS_MIN | 按需增长，兼顾同步Redis等待和HTTP处理；不预建400个线程 |
| accept-count | 1000 | AW_HTTP_ACCEPT_COUNT | 连接上限后的OS接入队列；不是业务请求或工作线程队列 |
| 请求行等待 | 20s | AW_HTTP_CONNECTION_TIMEOUT | 接收连接后等待请求数据；不是业务执行超时 |
| HTTP keep-alive空闲等待 | 30s | AW_HTTP_KEEP_ALIVE_TIMEOUT | 回收空闲HTTP连接；不设置WebSocket会话超时 |
| 每条HTTP连接最大请求数 | 1000 | AW_HTTP_MAX_KEEP_ALIVE_REQUESTS | 减少高频请求重新建连，同时保持有限复用 |
| Redis最大/最大空闲/最低空闲 | 200 / 100 / 8 | AW_REDIS_POOL_MAX / AW_REDIS_POOL_MAX_IDLE / AW_REDIS_POOL_MIN_IDLE | 最大空闲保留一定突发复用能力，最低空闲不随最大容量按比例扩大 |
| Redis借连接等待 | 1000ms | AW_REDIS_POOL_WAIT_MS | 池耗尽时有限等待；不无限积压 |
| MySQL最大/最低空闲 | 50 / 5 | AW_DB_POOL_MAX / AW_DB_POOL_MIN_IDLE | 避免跟随HTTP连接数放大数据库连接 |
| MySQL借连接等待 | 3000ms | AW_DB_POOL_WAIT_MS | 保留原有有限等待 |

Redis保留借出校验和空闲校验，取消归还校验，减少每次操作的一个同步PING；连接/读超时保留各环境已有的1000ms。MySQL空闲回收由10秒改为10分钟，减少低谷后的重复建连。协议、心跳频率、会话路由、认证和业务执行机制不变。

## 适用边界

- 5000个执行器若每15秒发送心跳，仅心跳就约333次/秒，每次还包含多次Redis往返；任务进度和HTTP请求需另计。400线程和200 Redis连接只是有界资源预算，不是吞吐保证。Redis延迟升高仍可能耗尽工作线程。
- 以8GiB实例为示例；400个平台线程还需要线程栈和本地内存。JVM堆、直接内存、连接缓冲区及其他线程必须共同留有余量，不能把全部内存分配给堆。
- 每个实例最多新增50个数据库连接和200个Redis连接预算，实例数增长时要核对共享服务总连接上限。不能仅靠扩大客户端池解决慢查询或慢Redis。
- 应用进程的nofile软限制建议至少32768；Linux `net.core.somaxconn` 至少1000，否则OS可能截断accept-count。应用容量配置不自动修改主机或容器设置。
- Spring Boot 2.7.18没有Tomcat工作队列容量的直接配置项。本次保留Tomcat原生执行器，不新增自定义排队/拒绝机制。accept-count不能限制已接收请求的工作排队长度。
- WebSocket连接不会因另一台上线自动均分；容量需要按最忙实例评估，不能只看集群总量。

## 发布观察与回退

观察每台实例的监听Recv-Q、Tomcat当前连接/忙线程、Redis借连接等待、数据库活动/等待连接、GC及HTTP延迟。连接或忙线程持续超过上限70%时开始排查；队列持续积压时不能继续单纯加大队列。主机检查可使用 `ss -lnt '( sport = :7001 )'`、`cat /proc/<java-pid>/limits` 和 `sysctl net.core.somaxconn`。

环境变量允许独立缩小配置；整体回退恢复发布前已记录的配置并滚动重启。长连接会重连，应逐台发布并观察。上述默认值不构成5000连接容量承诺；正式容量承诺需要在隔离环境下验证5000连接加代表性HTTP、心跳及任务流量的组合负载。

配置依据：[Spring Boot 2.7.18 参数](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/application-properties.html)、[Tomcat HTTP Connector](https://tomcat.apache.org/tomcat-9.0-doc/config/http.html)。
