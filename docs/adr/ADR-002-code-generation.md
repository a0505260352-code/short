# ADR-002：短码生成方案

- 状态：Accepted（2026-09-20）
- 相关：ADR-001（技术栈）、`V1__create_short_link.sql`

## 背景

短码要同时满足四条互相拉扯的要求：定长且短（跳转路由按 `/{code}` 单段匹配）、可判定来源类型
（哈希码 / 自定义码）、同一长链重复提交要产出**不同**短码（否则第二次提交的点击会污染第一份统计）、
以及在多实例并发下不可能发出两个相同短码。

## 决策

### 1. 生成：`Base62(MurmurHash3(url))`，定长 6

`CodeHasher.code(input)` = `MurmurHash3.hash32x86(utf8(input))` 按无符号 int 读 → Base62 → 左侧补 `0`
到 6 位。补 `0` 而非截断，宽度不足才是 bug。

- **为什么是 `hash32x86`：** commons-codec 1.18.0 里 `MurmurHash3.hash32(byte[])`（多数资料写的名字）
  **已废弃**，未废弃的只有 `hash32x86(byte[])`、`hash128(byte[])`、`hash128x64(byte[])`。
  ⚠️ 且 `hash32x86` 与废弃的 `hash32` **结果不同**（实测 4 组输入全不相交），换 API 时所有历史黄金值作废。
  现在钉在 `CodeHasherTest` 的黄金值是 `"https://example.com"` → `-929933992` → **`3fjKsi`**。
- **为什么不做折叠：** 早期设计取 `hash128x64` 的四段再 XOR 折叠，目的是让每个输入位都影响输出；
  x86_32 本身已满足，折叠是多余步骤、多余一类 bug。
- **为什么定长 6：** `62^6 = 5.68e10 > 2^32 = 4.29e9`，所以任意 32 位哈希都能表进 6 位而不撑宽；
  反过来「哈希码恒 6 位」让码族可以**只按长度判定**，不必查库。

### 2. 冲突：双触发器重试环，盐加在**哈希输入**上

```
hashInput = url
repeat ≤ shortlink.code.max-attempts(8):
    code = Base62(hash(hashInput))
    if bloom.possiblyExists(code) and mapper.existsByCode(code): hashInput = url + UUID; continue
    try insert → 成功则 bloom.add(code) 返回
    catch DuplicateKeyException: hashInput = url + UUID; continue
throw CodeExhaustedException  → 503
```

- **两个触发器分工不同：** `BF.EXISTS` 是便宜的预筛（确定不存在就跳过 DB 探测）；
  `uk_code` 上的 `DuplicateKeyException` 才是撑住系统的那个——布隆过滤器冷启动、重启、被 flush、
  随 Redis 实例丢失时，预筛完全不可信，唯一索引仍然可信。
- **盐只能加在输入上。** 加在产出码上会把码撑过 6 位，而 `/{code}` 路由与「按长度判族」都假设定宽。
  （这一度被误读，实现时明确纠正过。）
- **布隆是加速器、DB 是唯一仲裁者：** 预筛漏报的冲突 = 一次失败 insert + 一轮重哈希；预筛误报 = 一次多余的
  `existsByCode`。两种偏差都**不可能**发出重复码。
- **「同一长链重复提交产出新码」是涌现性质**，不需要额外代码：第一次提交已把 `hash(url)` 那个码占住，
  第二次提交在第 1 轮就撞上 → 加盐 → 新码。

### 3. 自定义（vanity）码：4–12 位，显式拒绝 6 位

`[0-9A-Za-z]{4,12}`；长度恰为 6 直接拒绝（把哈希族和 vanity 族按长度分开，于是「vanity 码是否可能被哈希
重现」这个判定根本不需要存在——实现期间写过 `Base62.decode` / `isHashReachable`，确认多余后删除）。
冲突**直接 409，绝不重投**：调用方要的就是这个码，静默换一个等于交付了另一条链接。
成功后同样要 `BF.ADD`（阶段 5 实测发现 `createVanity` 漏写，见「后果」）。

保留字集合 `{api, actuator, health, metrics, info, error}` 在 `ApplicationReadyEvent` 上对照真实的
`RequestMappingInfoHandlerMapping` 模式校验：新增顶级路由却没登记前缀，启动即失败。vanity 码撞上路由前缀
并不是路由事故（字面模式优先于 `/{code}`），而是**一条永远不跳转的死链**，所以要防的是静默而非报错。

### 4. 存储前提：`code` 列必须 `COLLATE ascii_bin`

表默认 `utf8mb4_0900_ai_ci` 大小写不敏感，会让 `a1B2c3` 与 `A1B2C3` 相等：**既破坏 `uk_code` 的唯一性，
又可能让跳转读错行，而且不报错**。Base62 是纯 ASCII，`ascii_bin` 既正确又每字节更省。

## 后果

- **码空间实际受 32 位哈希限制，不是 62^6。** 可达值 4.29e9；按生日累积，n 条链接的期望冲突对数约
  `n²/2N`，即 1e7 量级链接约 1.2e4 次冲突，每次多花一轮循环，`max-attempts=8` 足够。真到码空间吃紧
  （冲突率抬升表现为 `CodeExhaustedException` 出现）时，要做的是加宽码或换 64 位哈希——**加宽会破坏
  「按长度判族」和 vanity 的长度保留**，届时要一并处理。
- **短码不是秘密。** 哈希无密钥，知道算法就能从长链算出短码；同理穷举 4.29e9 空间即可探测存在的码。
  本服务不把它当访问控制（跳转本身就是公开行为），因此 v1 可以不做鉴权；**将来若出现「私有链接」需求，
  必须另加随机 token 族，不能指望这条哈希提供不可见性。**
- **布隆命令走 `EVAL` 而不是裸 dispatch：** Lettuce 6.6 的 `CommandType` 枚举里没有任何 `BF_*` 常量。
  `redis.call('BF.RESERVE'|'BF.ADD'|'BF.EXISTS')` 已对真 Redis 8.10.1 实测可用（含 `EXPANSION`）。
  副产品：`BF.RESERVE` 重复执行报 `item exists`，故 reserve 做成 best-effort 只记 debug；
  `BF.ADD` 会自动建过滤器，所以 reserve 失败只损失容量规划，不影响正确性。
- **阶段 5 抓到的真实缺陷：** vanity 码从未写进过滤器（只有哈希环的 `add` 路径会写），
  `BF.EXISTS bf:codes <vanity>` 实测为 0。按当前长度规则两族不相交，所以它**不会**导致错误跳转，
  修它防的是将来放开长度限制时的回归。
- **`BF.ADD` 与 insert 不在同一事务里**（Redis 无事务语义）：insert 成功后进程崩溃会留下
  「库里有码、过滤器里没有」的状态，后果只是那次冲突要靠 `DuplicateKeyException` 触发器兜住——
  这正是把唯一索引设计成第二触发器的原因。
