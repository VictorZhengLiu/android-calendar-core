# android-calendar-core

日历应用最难的三件事的 Java 实现：**重复规则、时区、重叠排版**。

这个仓库是两个可独立测试的库，都不依赖 Android 框架，只需要 JDK 17：

| 模块 | 解决的问题 | 测试 |
|---|---|---|
| [`rrule/`](rrule/) | RFC 5545 重复规则（`RRULE`）的解析与展开 | 53 个 |
| [`event-layout/`](event-layout/) | 日视图中重叠日程的分栏排版 | 41 个 |

两个模块都**不依赖 Maven 或 Gradle**，克隆下来直接跑：

```bash
bash run-tests.sh          # 在仓库根目录：跑全部测试
bash run-tests.sh --offline  # 已有 lib/ 下的 jar 时
```

首次运行会自动下载 JUnit 到各模块的 `lib/`。

---

## 为什么写这个

日历应用的核心难点从来不是增删改查。真正难的是三件事，而且每一件都有"看起来能跑、实际会出错"的陷阱：

| 难点 | 常见错误做法 | 这里的做法 |
|---|---|---|
| **重复规则** | 存 `+7天`、`+1个月` | 完整解析 RFC 5545，支持 `BYDAY` 序数、`BYSETPOS` |
| **时区与夏令时** | 存本地 `LocalDateTime` | 存「本地日期 + 时区」，渲染时再解析偏移 |
| **重叠日程排版** | 重叠就平分宽度 | Δt 相位分层 + 全组共享栏位池 + 硬性不重叠保证 |

---

## rrule — 重复规则引擎

```java
RRule rule = RRule.parse(
        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",   // 每月最后一个工作日
        LocalDate.of(2026, 10, 30).atTime(9, 0).atZone(ZoneId.of("Asia/Shanghai")));

List<RRuleOccurrence> occurrences =
        rule.expand(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 31));
// 2026-10-30, 2026-11-30, 2026-12-31, 2027-01-29, 2027-02-26, 2027-03-31
```

**支持的部件**：`FREQ`（DAILY/WEEKLY/MONTHLY/YEARLY）、`INTERVAL`、`COUNT`、`UNTIL`、`WKST`、`BYMONTH`、`BYMONTHDAY`（含负数）、`BYDAY`（含 `-1FR` 这类序数）、`BYSETPOS`。

**明确拒绝**（抛异常而非静默忽略）：`HOURLY`/`MINUTELY`/`SECONDLY`、`BYHOUR`/`BYMINUTE`/`BYSECOND`、`BYWEEKNO`/`BYYEARDAY`。

细节和取舍见 [`rrule/README.md`](rrule/README.md)。

---

## event-layout — 重叠日程排版引擎

```
09:00 | 1 |   |
09:30 | 1 | 2 |
10:00 |   | 2 |
```

```java
List<LayoutEvent> day = List.of(
        LayoutEvent.at("a", "站会", "09:00", "10:00"),
        LayoutEvent.at("b", "评审", "09:30", "10:30"));

EventLayoutEngine.Result result = new EventLayoutEngine().layout(day);
for (PlacedEvent placement : result.placements()) {
    double left = placement.leftFraction();
    double width = placement.widthFraction();
}
```

**核心保证**：时间重叠的两个日程，横向范围绝不相交——不是"通常不会"，是构造上不可能。这条不变量以**公开校验器**暴露，调用方可以直接断言：

```java
List<EventLayoutEngine.Violation> problems = EventLayoutEngine.validate(result);
```

流程：分组（传递冲突）→ Δt 相位分层 → 冲突块划分 → 全组共享栏位池。

细节、取舍和被测试推翻的 4 个设计见 [`event-layout/README.md`](event-layout/README.md)。

---

## 目录结构

```
android-calendar-core/
├── run-tests.sh              # 一次跑全部模块的测试
├── rrule/
│   ├── README.md             # 支持矩阵、设计取舍、缺陷复盘
│   ├── run-tests.sh
│   ├── pom.xml               # 想用 Maven 也可以
│   └── src/{main,test}/java/com/liuzheng/rrule/
└── event-layout/
    ├── README.md             # 算法、不变量、取舍
    ├── run-tests.sh
    ├── pom.xml
    └── src/{main,test}/java/com/liuzheng/calendarlayout/
```

每个模块也自带 `run-tests.sh` 和 `pom.xml`，可以单独使用或单独构建。

---

## 环境要求

- **JDK 17**（用了 `java.time`、`record` 之外的 Java 17 特性有限，主要是 `java.time` 的完整时区数据库）
- 跑测试需要一个 JUnit jar，首次运行脚本会自动下载；离线环境可预先放进 `lib/`

---

## 测试思路

两个模块的测试都遵循同一条原则：**断言不变量，而不只是断言输出**。

- rrule：`validate` 式的边界测试 + 每个日期**手工从日历推导**（不是从实现输出抄回来的，否则测试只能证明代码没变、不能证明代码对）
- event-layout：**每个场景都断言 `validate` 通过**，另加 3000 次固定种子的随机化不变量检查（固定种子 → 失败可复现）

两个 README 都记录了开发过程中被测试逼出来的真实缺陷，因为**这类库的错误几乎总是静默的**：它会返回看似合理的日期或布局，几个月后才被发现。

---

## 后续计划

- [ ] `EXDATE` / `RDATE` 支持（rrule）
- [ ] `BYYEARDAY` / `BYWEEKNO`（rrule）
- [ ] 时间粒度展开，`HOURLY` 等（rrule）
- [ ] 多日视图与跨天日程裁剪（event-layout）
- [ ] Android UI 层（日视图 / 月视图）—— 这两个库是纯 Java，可从 Kotlin 直接调用

---

## 许可

MIT
