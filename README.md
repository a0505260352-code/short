# short

短链接服务 v1：创建短链 → 302 跳转 → 异步点击统计，含有效期、自定义短码、URL 校验与限流。
决策依据见 `docs/adr/`。

## 本地开发

需要 Docker（跑 MySQL / Redis / RocketMQ）与 JDK；**不需要安装 Maven**，构建走仓库内的 Maven Wrapper。

```bash
git clone https://github.com/a0505260352-code/short.git
cd short
docker compose -f docker/docker-compose.yml up -d   # MySQL 映射到宿主 13306（3306 已被占用）
./mvnw spring-boot:run                              # 应用监听 8080
```

```bash
./mvnw test                       # 需要 Docker：Testcontainers 自行起真 MySQL/Redis
SHORTLINK_MQ_TEST=true ./mvnw test # 追加端到端点击链路，需要上面的 broker
```

## 分支约定

主分支为 `main`。
