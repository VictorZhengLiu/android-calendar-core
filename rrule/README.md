# RRULE 展开器（Java）

RFC 5545 重复规则（`RRULE`）的解析与展开实现，面向日历类应用。

一个「每月最后一个工作日」的规则，用 `+1 个月` 是算不出来的；一个「每天 09:00」的日程，在夏令时切换当天会漂移一小时。这个库解决的就是这类问题。

```java
RRule rule = RRule.parse(
        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",   // 每月最后一个工作日
        LocalDate.of(2026, 10, 30).atTime(9, 0).atZone(ZoneId.of("Asia/Shanghai")));

List<RRuleOccurrence> occurrences = rule.expand(LocalDate.of(2026, 10, 1),
                                               LocalDate.of(2027, 3, 31));
// 2026-10-30, 2026-11-30, 2026-12-31, 2027-01-29, 2027-02-26, 2027-03-31
```

（`DTSTART` 取 10 月 30 日，因为它本身就是当月的最后一个工作日；若取一个不满足规则的日期，
该日期会作为第一个实例出现，详见下文「关于 `DTSTART` 的语义」。）

---

## 为什么写这个

日历应用的核心难点从来不是增删改查，而是三件事：

| 难点 | 常见错误做法 | 这个库的做法 |
|---|---|---|
| **重复规则** | 存 `+7天`、`+1个月` | 完整解析 RFC 5545，支持 `BYDAY` 序数、`BYSETPOS`、`EXDATE` 语义 |
| **时区与夏令时** | 存本地 `LocalDateTime` | 存「本地日期 + 时区」，渲染时再解析偏移 |
| **重叠日程排版** | 重叠就平分宽度 | 另见姊妹模块（分层 + 三类时间块 + 并列块） |

---

## 构建与测试

只需要 JDK 17，**不依赖 Maven 或 Gradle**：

```bash
bash run-tests.sh              # 首次会自动下载 JUnit 到 lib/
bash run-tests.sh --offline    # 已有 lib/ 下的 jar 时
```

也可以用 Maven：

```bash
mvn test
```

跑示例：

```bash
javac -d build/classes src/main/java/com/liuzheng/rrule/*.java
java -cp build/classes com.liuzheng.rrule.RRuleDemo
```

---

## 支持的规则

| 部件 | 状态 | 说明 |
|---|---|---|
| `FREQ` | ✅ | `DAILY` / `WEEKLY` / `MONTHLY` / `YEARLY` |
| `INTERVAL` | ✅ | 必须 ≥ 1 |
| `COUNT` | ✅ | 统计的是**实例数**，不是周期数 |
| `UNTIL` | ✅ | 包含边界；支持 `YYYYMMDD`、`YYYY-MM-DD`、ISO 日期时间三种写法 |
| `WKST` | ✅ | 默认周一 |
| `BYMONTH` | ✅ | |
| `BYMONTHDAY` | ✅ | 支持负数（`-1` = 当月最后一天）；**不存在的日期跳过，不做钳制** |
| `BYDAY` | ✅ | 支持序数（`1FR`、`-1MO`） |
| `BYSETPOS` | ✅ | 支持负数 |
| `BYHOUR` / `BYMINUTE` / `BYSECOND` | ❌ 明确拒绝 | 需要「时间粒度」展开；本库的时间部分一律取自 `DTSTART` |
| `BYWEEKNO` / `BYYEARDAY` | ❌ 明确拒绝 | 显式抛异常，而不是静默忽略 |

**拒绝的方式是抛 `RRuleException` 并给出可操作的错误信息**，不会静默产生错误结果：

```
FREQ=HOURLY requires time-of-day expansion, which this implementation does not support
```

### 有意的取舍（与 RFC 5545 的差异）

1. **展开粒度为「天」。** 每个实例的时间部分都等于 `DTSTART` 的时间。这对「每天 09:00」这类日历场景是正确模型，也避免了时间粒度展开带来的歧义。
2. **`FREQ=MONTHLY` 遇到不存在的日期是跳过而非钳制。** 1 月 31 日的规则在 2 月不产生实例，而不是落到 2 月 28 日。RFC 5545 把这一点留给实现，跳过符合用户对日历的预期。
3. **`WKST` 默认周一**（RFC 5545 的默认值），而不是跟随语言环境。
4. **查询窗口是过滤器，不是起点。** 展开永远从 `DTSTART` 开始走，所以 `COUNT` / `UNTIL` 的语义与「从头展开」完全一致——查询明年 3 月，仍然遵守整条规则的次数上限。

---

## API

### 解析

```java
RRule rule = RRule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10", zonedStart);
```

`DTSTART` 承载两件事：第一个周期的锚点，以及每个实例的时间与时区。

也可以用 Builder 逐个部件构建（适合数据已经存在数据库列里的场景，不必先拼成字符串再解析）：

```java
RRule rule = RRule.builder()
        .frequency(RRule.Frequency.MONTHLY)
        .byDay(2, DayOfWeek.TUESDAY)   // 每月第二个周二
        .count(3)
        .start(zonedStart)
        .build();
```

### 展开

```java
List<RRuleOccurrence> inWindow = rule.expand(windowStart, windowEnd);  // 两端包含
List<RRuleOccurrence> nextTwoMonths = rule.expand(today);              // 默认 62 天窗口
```

### 关于 `DTSTART` 的语义（容易踩坑的一点）

**`DTSTART` 永远是第一个实例，即使它不满足 `BY*` 条件。**

```java
// 2026-10-12 是周一，而规则只选周二
RRule r = RRule.parse("FREQ=WEEKLY;BYDAY=TU;COUNT=3",
        LocalDate.of(2026, 10, 12).atTime(9, 0).atZone(ZoneId.of("Asia/Shanghai")));
// => 2026-10-12, 2026-10-13, 2026-10-20
```

**为什么这样定**：用户在某天创建一条「每周二」的日程，如果那一天是周一，他仍然期望看到自己刚建的日程出现在周一。Google Calendar 就是这个行为。若改为「严格按规则过滤掉 DTSTART」，用户会以为新建失败了。

**当前的可选实例仍受限制**：早于 `DTSTART` 的候选一律丢弃。所以 `FREQ=MONTHLY;BYMONTHDAY=1,15` 从 10 月 12 日起算时，不会补出一个 10 月 1 日——尽管它满足规则。

这两个规则的组合是：**`DTSTART` 一定在集合里，但集合里不会有比它更早的东西。**

### 实例

```java
RRuleOccurrence o = inWindow.get(0);
o.date();              // LocalDate —— 本地日期
o.time();              // LocalTime —— 取自 DTSTART
o.zone();              // ZoneId
o.startInstant();      // Instant —— 按该日期解析偏移后的绝对时刻
o.endInstant(90);      // 90 分钟后的结束时刻
```

**夏令时跳变**：本地时间在该日期不存在时（春季调快一小时），偏移会向前推移，实例不会消失。

```java
// 纽约 2026-03-08 的 02:00-03:00 被跳过，02:30 的实例落到 03:30
RRule r = RRule.parse("FREQ=DAILY;COUNT=2", mar7at0230NewYork);
// => 2026-03-07T02:30-05:00, 2026-03-08T03:30-04:00
```

### 描述

```java
rule.humanReadable(Locale.SIMPLIFIED_CHINESE);
// "每月的 第 2 个星期二，共 3 次"
// "每年 十二月 25 日，共 3 次"
```

只描述规则里真实存在的部件，不会用默认值填充。因此 `humanReadable` 的输出**永远不夸大规则**，可以直接展示给用户。

---

## 开发过程中发现并修复的 4 个真实缺陷

这些不是「写完就过」的代码——测试逐条把它们逼了出来。记录下来，因为**一个重复规则引擎的错误几乎总是静默的**：它会返回看似合理的日期，用户几个月后才发现日程少了一次。

### 1. `BYMONTHDAY` 的存在性判断错误（严重）

用 `byMonthDay != null` 判断规则里有没有 `BYMONTHDAY`，但数组在首次 `byMonthDay(...)` 调用时就已经分配了。于是**任何规则都被判定为「有 BYMONTHDAY」**，过滤器把当月所有日期都筛掉。

表现：`FREQ=MONTHLY;COUNT=3` 返回 `[15日, 16日, 17日]` 这种连续日期。

修法：改成扫描数组里是否有置位的元素，而不是判空。同类问题在 `BYSETPOS` 上也存在。

### 2. 缺少「以 `DTSTART` 的日为准」的锚点过滤（严重）

`FREQ=MONTHLY;COUNT=3` 里没有任何 `BY*` 部件指定日期，此时应当以 `DTSTART` 的日号为准。但月度分支没有做这层过滤，把当月每一天都当成了候选。

这个缺陷和缺陷 1 叠在一起，导致所有月度规则的输出完全错误。

### 3. `COUNT` 在窗口过滤之前就自增（严重）

`COUNT` 限制的是**整条规则的实例数**，但计数器在「判断实例是否落在查询窗口内」之前就加一了。结果是窗口外的实例白白消耗了额度，查询结果比预期少——典型表现是**每次查询都少一个实例**。

修法：计数器只统计通过 `DTSTART` 边界与 `UNTIL` 的实例，窗口过滤不消耗额度。这里有个必须保留的区分：窗口外的实例**必须**计入 `COUNT`，否则晚开的窗口会返回超过上限的实例；但**早于 `DTSTART` 的候选不是规则的实例**，不能计入。

### 4. `YEARLY` 周期以「年首」为锚点（中等）

周期起点用了 `startDate.withDayOfYear(1)`。于是 `FREQ=YEARLY;BYMONTH=1;BYDAY=1FR`（每年 1 月第一个周五）的第一个周期从 1 月 1 日开始，而规则自身的 `DTSTART`（1 月 2 日）落在这个周期内——下一年 1 月的第一个周五本该属于下一个周期，却被归到了当前周期并被跳过。

修法：年度周期的锚点改为 `DTSTART` 自身的月日，即 `startDate.plusYears(...)`。

---

## 数据流

```
RRULE 字符串
    │  RRule.parse()
    ▼
RRule（不可变）  ──  FREQ / INTERVAL / COUNT / UNTIL
    │                 BYMONTH / BYMONTHDAY / BYDAY / BYSETPOS
    │  expand(windowStart, windowEnd)
    ▼
RRuleExpander
    │
    │  按周期迭代：天 → 周 → 月 → 年
    │  每个周期内按 RFC 5545 规定的顺序过滤：
    │      BYMONTH → BYMONTHDAY → BYDAY → BYSETPOS
    ▼
List<RRuleOccurrence>（LocalDate + LocalTime + ZoneId）
```

**过滤顺序是 RFC 5545 强制的**，顺序错了会在简单输入上看起来正常、在真实日历上悄悄漂移。

`RRule` 是线程安全的（不可变 + 按需缓存 expander）；`RRuleExpander` 不是，它只服务于单个规则的展开过程。

---

## 测试

52 个测试，覆盖解析、四种频率、各 `BY*` 组合、`BYSETPOS`、边界情况、夏令时与描述文本。

**测试里的每个日期都是手工从日历推导的，不是从实现输出里抄回来的**——否则测试只能证明代码没变，不能证明代码对。文件头部写明了这一点。

几个值得注意的用例：

- `monthlySkipsMonthsThatLackTheStartDayRatherThanClamping` —— 固化「跳过而非钳制」这个取舍
- `yearlyOrdinalByDayIsRelativeToTheYearWhenByMonthIsAbsent` —— RFC 5545 里最容易读错的一条：没有 `BYMONTH` 时，`YEARLY` 的 `BYDAY` 序数相对**年**；有 `BYMONTH` 时相对**月**
- `daylightSavingGapShiftsTheOccurrenceForward` —— 夏令时跳变时实例不消失
- `dtstartIsTheFirstInstanceEvenWhenItDoesNotMatchTheRule` —— 固化上面那个语义决定
- `windowFiltersResultsButDoesNotRestartTheSeries` —— 窗口不重置 `COUNT`
- `humanReadableOmitsWhatTheRuleDoesNotSay` —— 描述文本不夸大规则

---

## 后续计划

- [ ] `EXDATE` / `RDATE` 支持
- [ ] `BYYEARDAY` / `BYWEEKNO`
- [ ] 时间粒度展开（`HOURLY` / `MINUTELY`，以及 `BYHOUR`）
- [ ] 与 `lib-recur`、`ical4j` 做差分测试，逐条核对边界行为

---

## 许可

MIT
