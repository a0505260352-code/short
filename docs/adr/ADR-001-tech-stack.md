# ADR-001：技术栈与 MQ 接入层选型

- 状态：Accepted（2026-09-20）
- 相关：ADR-002（短码生成）

## 背景

短链接服务 v1 需要同时满足：Maven + MyBatis-Plus 数据层、Redis（布隆过滤器 + 跳转缓存）、RocketMQ
异步点击统计、Maven Wrapper 自举（不装全局 Maven）。约束不在业务侧，而在**依赖矩阵**侧：RocketMQ 的
Spring 生态只有一份公开的官方兼容表，而它并不覆盖最新的 Spring Boot 大版本。

三个候选：

| 方案 | 组合 | 问题 |
|---|---|---|
| A | Boot 4.1.1 + `rocketmq-spring 2.3.6` | 该库整条 2.3.x 线的 parent POM 声明 `spring.boot.version = 2.7.18`，Boot 3/4 **都在其构建矩阵之外**。能跑通不等于被支持：Bean 装配语义漂移不会以缺类形式出现，实证只能排除 `NoClassDefFoundError`。 |
| B | Boot 3.5.16 + Spring Cloud 2025.0.3 + SCA 2025.0.0.0（`spring-cloud-starter-stream-rocketmq`） | Boot 3.5 已过 OSS EOL（2026-06-30）。 |
| C | Boot 4 + 裸 `rocketmq-client` 自写封装 | 矩阵安全，但把 producer/consumer 生命周期、重试、ack 语义全部变成自维代码。 |

## 决策

**选 B。** 唯一公开的官方矩阵来自 Spring Cloud Alibaba（`2025.0.x ↔ Boot 3.5.x`、
`2025.1.x ↔ Boot 4.0.x`），因此「矩阵内 + 已过 EOL」优先于「矩阵外 + 仍在支持期」——
这是用户在知悉 EOL 事实后明确确认的取舍。

落地坐标（全部实测确定，非推导）：

| 坐标 | 版本 |
|---|---|
| `spring-boot-starter-parent` | 3.5.16（Spring Framework 6.2.19，本机 JDK 25.0.4） |
| `spring-cloud-dependencies` / `spring-cloud-alibaba-dependencies` | 2025.0.3 / 2025.0.0.0（BOM import） |
| `spring-cloud-starter-stream-rocketmq` | 由 SCA BOM 管，传递 `rocketmq-client 5.3.1` |
| `mybatis-plus-spring-boot3-starter` / `mybatis-plus-jsqlparser` | 3.5.17（Boot 3 用 `boot3` 而非 `boot4`；MP 3.5.9 起 JSqlParser 拆包） |
| `flyway-core` + `flyway-mysql` | 11.7.2（Flyway 9.18 起 MySQL 方言独立成包） |
| `commons-codec` | 1.18.0（API 变更见 ADR-002） |
| Testcontainers | 1.21.4（Boot 3.5 BOM 管的是 1.x） |
| 镜像 | `mysql:8.4` / `redis:8.10.1` / `apache/rocketmq:5.3.1`（broker 镜像必须匹配 client） |

## 后果

1. **编程模型换了，不只是版本换了。** SCA binder 走 Spring Cloud Stream 的函数式绑定
   （`Supplier`/`Consumer` bean + `spring.cloud.stream.bindings.*` + `StreamBridge`），
   **没有** `RocketMQTemplate`、**没有** `@RocketMQMessageListener`。
2. **`spring.cloud.function.definition` 指了不存在的函数会让上下文启动失败**，所以 MQ 配置必须与
   consumer bean 同步落地，配置不能领先于代码。
3. **`fastjson2` 必须显式钉 2.0.58**：`rocketmq-common 5.3.1` 传递 2.0.43，SCA binder 传递
   `fastjson2-extension 2.0.58`，Maven nearest-wins 会把 core 钉在旧版上，而 fastjson2 不容忍
   core/extension 版本劈叉。这是换 binder 换来的新坑，方案 A/C 都没有它。
4. **点击发送必须钉 `send-type: OneWay`。** `RocketMQProducerProperties` 默认 `Sync` +
   `sendMsgTimeout=3000` + `retryTimesWhenSendFailed=2`，broker 不可用时每次跳转会同步阻塞最多 9 秒，
   正好把「统计链路」重新绑回「跳转链路」。由于 `@ConfigurationProperties` 默认
   `ignoreUnknownFields=true`，属性名写错只会静默退回 Sync——这条要靠核对 binder 字节码保证，
   跑通不能保证。
5. **消费端抛异常换重投在这条 binder 上不成立。** `RocketMQInboundChannelAdapter.consumeMessage` 先把
   监听器包进 SCSt 的 `RetryTemplate`，重试耗尽后走 `RecoveryCallback`（发到 errorChannel 被默认
   errorLogger 吞掉），异常不再外抛 ⇒ binder 返回 `CONSUME_SUCCESS` ⇒ 消息被确认丢失。反压因此改为
   阻塞消费线程（`offer(timeout)`），靠 RocketMQ 拉取流控把积压留在 broker。
6. **过期不占用 MQ。** 原计划用「延迟级别阶梯重投」把到期链接的 `status` 翻成 EXPIRED，实现阶段 7 时
   判定为不划算并删除：跳转路径从不单独信任 `status`（`isLive()` 按 `valid_until` 判定，缓存 TTL 又钳到
   同一时刻），所以到期瞬间链接就停了，延迟消息只能让统计接口里的字段早 10 分钟变准，代价是每条 30 天
   链接重投约 360 次。改由 `ExpirySweepTask` 每 10 分钟分批清扫，`short-link-expiry` topic 因此不存在。
   若将来要求「到期即时可见」（链接回收、通知、按 status 检索），需要重新评估这条偏离。

## 验证记录

阶段 0 用一次性 spike（`MqSpikeConfig` + `application-mq-spike.yml`，`--spring.profiles.active=mq-spike`）
对真 broker 做了 POJO 往返实测，结论为
`SPIKE-PASS round trip + POJO deserialization identical`，上下文启动 4.469s。该 spike 已随本 ADR 落地删除，
其证明的结论由本文件承载。
