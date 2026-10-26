# 官方 elasticsearch 源码（vendored）与补丁清单

`fs_elasticsearch` 的主体是 **官方 483 tag 的 `plugin/trino-elasticsearch` 源码**，原样收在本仓库里，只在 keyword 子字段（multi-field）、text 字段 `LIKE '%...%'` 短语下推、nested 对象数组和 `any_match` nested 下推这几部分打了补丁，共涉及 7 个官方文件。其余官方功能、配置项和类型映射保持原样。

| 项 | 值 |
| --- | --- |
| 上游 | <https://github.com/trinodb/trino/tree/483/plugin/trino-elasticsearch>（Apache License 2.0，文件头原样保留） |
| 上游文件 | 52 个（`src/main/java`，不含官方测试代码） |
| 本仓库新增 | 4 个：`FsElasticsearchConnectorFactory`、`FsElasticsearchPlugin`、`ElasticsearchNestedPredicateTranslator`、`ElasticsearchNestedQuery` |
| 落点 | `src/main/java/io/trino/plugin/elasticsearch/` |
| 与上游不一致的文件 | 7 个，全部带 `// PATCH(fs-trino)` 注释 |
| 校验 | `sha256sum -c docs/vendored-elasticsearch.sha256`（45 个未改动文件逐个与上游比 sha256） |
| 编译依赖 | 全部来自 Maven Central，版本按官方 483 的 `pom.xml` 锁定，见 `build.gradle` |

构建不需要 docker、不需要官方镜像。

## 为什么 vendored，而不是依赖官方发布的 jar

483 的 `io.trino:trino-elasticsearch` **没有发布到 Maven Central**（该坐标只发布到 476，可对比 <https://repo1.maven.org/maven2/io/trino/trino-elasticsearch/>），拿不到编译产物。

更关键的是：vendored 之后补丁类和官方类在同一个 package、同一个 classloader 里，可以直接用包私有成员。这是补丁能集中在 7 个文件的原因——例如 `IndexMetadata` 的内部类型（`PrimitiveType` / `ScaledFloatType` / `ObjectType`）和 `ScanQueryPageSource` 的解码流程本来都是包私有、public API 里看不见的。若改成在外部依赖的官方 jar 上做包装，只能用 public API，改动面会大得多，正是要避免的。

## 补丁清单

只有这 7 个文件与上游不同。`grep -rn 'PATCH(fs-trino)' src/main/java` 可以一次看全。

### 1. `client/IndexMetadata.java` — 新增列类型标记

`Type` 的 `@JsonSubTypes` 里登记 `multi_field` 和 `nested`，并新增 `record MultiFieldType()`、`record NestedType(List<Field> fields)`。

列句柄要在 coordinator 序列化、在 worker 反序列化，所以多出来的列类型必须能过 Jackson。走 `@JsonSubTypes` 而不是自定义 serializer，是为了不动上游的序列化路径。

`NestedType` 不能继续复用 `ObjectType`：ES 的 nested 值保留对象边界，通常是对象数组；只有单独保留 nested 类型，元数据层才能稳定映射成 `ARRAY(ROW(...))`。

### 2. `client/ElasticsearchClient.java` — 解析子字段和 nested mapping（`parseType`）

`nested` 进入独立的 `NestedType` 分支，其余子字段继续按原逻辑解析。为了便于无 ES 服务测试，`parseType` 和 `nullSafeNode` 改为包内可见的静态方法，不改变解析行为。

父字段解析完之后，遍历它 `fields` 块里的每个子字段：

- **只注册 `type=keyword` 的子字段**。keyword 一定有 doc value，可以用 `docvalue_fields` 取回来；text 等子字段没有 doc value，注册了也读不出值，直接忽略并打 debug 日志。
- 列名是子字段在 Elasticsearch 里的真实路径：`父字段名.子字段名`（mapping 里就是 `name.keyword` 这个键）。
- `isArray` 默认**继承父字段**（multi-field 存的是父字段值的副本，父字段是数组它也是数组），可用 `_meta.trino["父字段.子字段"].isArray` 单独覆盖。
- `object` / `nested` 下的子字段不处理——那是对象内部字段，不是 multi-field。

### 3. `ElasticsearchMetadata.java` — 子字段列的类型与谓词下推

- `MultiFieldType` → `varchar` + `VarcharDecoder`（走 `document.fields()`，见补丁 4）。
- `supportsPredicates`：keyword 子字段与 keyword 字段同等对待。Elasticsearch 接受点号路径做 `term` / `range` / `regexp` 查询，所以 `WHERE "name.keyword" = 'x'` 依然会被下推成 ES 查询，而不是拿回来在 Trino 里过滤。
- `NestedType` → `ARRAY(ROW(...))` + `ArrayDecoder(RowDecoder)`。无论 ES 保存的是一个对象还是对象数组，都按数组读取，且 `_meta.trino.<字段>.isArray=true` 不会重复包成二维数组。
- nested 的叶子谓词不做普通点路径下推，避免绕过 ES 的 `nested` query 语义。
- `any_match(ARRAY(ROW(...)), row -> predicate)` 进入第 6 节专门翻译；`UNNEST` 后的过滤暂不额外下推。

### 4. `ScanQueryPageSource.java` — 从 `fields` 取值，而不是 `_source`

- 子字段列全部加进 `documentFields`（→ 请求体里的 `docvalue_fields`）。
- 从 `requiredFields`（→ 请求体里的 `_source.includes`）里**排除**子字段列：它们不在 `_source` 里，留着会白取甚至报错。
- 取值：命中的 `fields` 里是数组，数组列给整个 list，标量列取第一个；没有值时给 `NULL`。

### 5. text 字段 `LIKE '%...%'` → `match_phrase`

- `ElasticsearchMetadata.java`：`applyFilter` 遇到 text 字段上的 `LIKE '%literal%'` 时，把字面量写入表句柄的 `phrases`；不能表达成单一短语的模式（`_`、前缀/后缀通配符、中间通配符等）继续留在 Trino 侧过滤。
- `ElasticsearchTableHandle.java`：新增 `phrases` 字段，随表句柄序列化到 worker，并在 `toString()` 中显示为 `phrases=[字段:短语]`，方便用 `EXPLAIN` 验证。
- `ElasticsearchQueryBuilder.java`：把 `phrases` 生成 `{"match_phrase": {"字段": "短语"}}`，放入 `bool.filter`。
- `ScanQueryPageSource.java` / `CountQueryPageSource.java`：把 `phrases` 传给查询构建器，保证普通扫描和 `count(*)` 使用同一套过滤条件。

`match_phrase` 使用字段的 search analyzer，和字符子串语义的 `LIKE` 并不等价；这里依赖本环境在 ES 侧的分词/analyzer 插件处理短语语义。

### 6. `any_match(ARRAY(ROW(...)), row -> predicate)` → ES `nested` query

- `ElasticsearchMetadata.java`：`applyFilter` 识别 `any_match`，调用新增的 `ElasticsearchNestedPredicateTranslator`。翻译成功后才把该表达式从 Trino 剩余过滤条件中移除。
- `ElasticsearchNestedPredicateTranslator.java`：沿 lambda 参数解析 `ROW` 子字段路径，生成 `{"nested":{"path":"父字段","query":...}}`。同一个 `any_match` 内的 `AND` 会放进同一个 nested query，因此多个条件必须由同一条 nested 对象满足；`OR` 使用 nested query 内的 `should + minimum_should_match=1`。
- 支持 `=`、`<>` 和大小比较。`<>` 同时要求字段存在，避免把 SQL 的 `NULL` 结果误判为 true。text、scaled_float 等不适合直接 `term`/`range` 的字段保持 Trino 侧过滤。
- `ElasticsearchNestedQuery.java` 和 `ElasticsearchTableHandle.java`：已翻译的 nested query 随表句柄序列化到 worker，并在 `EXPLAIN` 中显示在 `nestedQueries` 下。
- `ElasticsearchQueryBuilder.java`、`ScanQueryPageSource.java`、`CountQueryPageSource.java`：普通扫描和 `count(*)` 使用同一组 nested 过滤条件。

多个顶层 `any_match` 条件会被翻译成多个独立 `nested` query，各自可以匹配不同的 nested 对象，和 Trino 对多个 `any_match` 的 AND 语义一致。

## 编译参数：`-parameters` 不能删

官方用 Maven 编译，class 文件里带 `MethodParameters` 属性；Gradle 默认不带，所以 `build.gradle` 里显式加了 `-parameters`。

原因在这批官方 descriptor 上：它们的 `@JsonCreator` 构造器**参数上没有 `@JsonProperty`**，例如

```java
@JsonCreator
public Descriptor(String path) { ... }
```

Jackson 只能靠 class 文件里的参数名认出 `path`。参数名一丢，这个构造器既不是 delegating 也不是 property-based，worker 反序列化 plan fragment 里的列句柄就直接失败——列句柄是 coordinator 发给 worker 的，所以任何一次读 ES 数据的查询都会挂。

症状很绕：ES 本身完全正常，报错却指向 coordinator 拉任务状态拿到了非 JSON 响应。

```text
Query failed: Unexpected response from https://<node>:8443/v1/task/<queryId>.0.0.0?summarize
Caused by: Cannot construct instance of `io.trino.plugin.elasticsearch.decoders.VarcharDecoder$Descriptor`
           (although at least one Creator exists): cannot deserialize from Object value
           (no delegate- or property-based Creator)
           ... through reference chain: TaskUpdateRequest["fragment"] -> ... -> ElasticsearchColumnHandle["decoderDescriptor"]
```

验证（必须打印出参数名 `path`）：

```bash
javap -v -p -cp build/libs/fs-trino-483.jar 'io.trino.plugin.elasticsearch.decoders.VarcharDecoder$Descriptor' | grep -A3 MethodParameters
```

我们自己写的 `http` 连接器不受影响：它的 `@JsonCreator` 参数都标了 `@JsonProperty`，所以这个坑只会以"只有 ES 查询失败"的形式出现。

## 打包：插件目录里不要有重复类

官方 `plugin/trino-elasticsearch/pom.xml` 对运行时依赖做了两处显式处理，`build.gradle` 里对应照抄了，别删：

- **排除 `commons-logging`**：官方在 `httpasyncclient` / `httpclient` / `elasticsearch-rest-client` 三处各排除一次。这些类已经由 airlift `log-manager` 带进来的 `jcl-over-slf4j` 提供，两份同时在插件目录里会出现重复类，httpclient 的日志走到哪一边取决于加载顺序。
- **guice 用 `classes` classifier**：官方 pom 和 `trino-plugin-toolkit` 都是这么写的。不写 classifier 会把普通 jar 一起带进来，同一个 `com.google.inject.*` 两份共存，而且两份的字节并不相同（普通 jar 还多出一批 ASM 移位类）。

插件目录里出现重复类不会立刻报错，只在真集群上以诡异的方式表现，所以每次动依赖都扫一遍：

```bash
cd /tmp && rm -rf dupcheck && mkdir dupcheck && cd dupcheck &&
  jar xf <仓库>/build/fs-trino-483.zip &&
  for j in *.jar; do jar tf "$j" | grep '\.class$' | grep -v '^META-INF/versions/' | sed "s|^|$j |"; done > all.txt &&
  awk '{print $2}' all.txt | sort | uniq -d | sed 's/^/DUP: /'
```

输出为空才算过。另外，`json`/`bootstrap`/`configuration`/`log` 等 airlift 依赖会带进 jetty、netty、log4j、logback-core 这些传递依赖——官方同样带着，不是问题（官方工厂里有 `doNotInitializeLogging()`，不会去碰服务器的日志配置）。

## 与官方一致的部分（没有改）

配置项（`elasticsearch.*` 全部属性）、隐藏列 `_id` / `_source` / `_score`、除 nested 外的类型映射、除第 5 节之外的其他谓词下推、按 shard 并行读、`count(*)` 走 count API、`raw_query` 表函数、`nodes` 系统表、`_meta.trino` 的 `isArray` / `asRawJson`、认证方式（`PASSWORD` / `AWS` / keystore / truststore）。

## 怎么确认"只有这 7 处"

```bash
grep -rn 'PATCH(fs-trino)' src/main/java
sha256sum -c docs/vendored-elasticsearch.sha256    # 未改动的 45 个文件必须全是 OK
git diff --stat <上一次同步上游的 commit> -- src/main/java/io/trino/plugin/elasticsearch
```

## 升级 Trino 版本

1. 取新 tag 的官方源码，覆盖 `src/main/java/io/trino/plugin/elasticsearch/`（保留本仓库新增的 `FsElasticsearchConnectorFactory` / `FsElasticsearchPlugin`，以及 7 个文件里的 `PATCH(fs-trino)`）。源码可整份 clone 到本地，也可以按文件从 `https://raw.githubusercontent.com/trinodb/trino/<tag>/plugin/trino-elasticsearch/src/main/java/io/trino/plugin/elasticsearch/<相对路径>` 取。
2. 对照上面的补丁清单，确认 7 个文件的上下文没有漂移（官方若重构过 `parseType` / `ScanQueryPageSource` / LIKE 下推，补丁要跟着改）。
3. 同步 `build.gradle` 里 ES rest-client、AWS SDK、airlift 的版本——新 tag 的 `plugin/trino-elasticsearch/pom.xml` 里锁着这些版本号。顺带确认两件容易丢的东西还在：`compileJava` 的 `-parameters`，以及打包的两条排除（guice 用 `classes` classifier、排除 `commons-logging`），见上方两节。
4. 重新生成校验清单：`docs/vendored-elasticsearch.sha256`（换 tag 后 sha256 全变），生成方式见文件头注释。
5. 跑验收：`gradle clean build`，再按 README 的验收 SQL 在本地 Trino 上过一遍（重点跑 keyword 子字段列、nested 对象数组的查询与 `DESCRIBE`）。
