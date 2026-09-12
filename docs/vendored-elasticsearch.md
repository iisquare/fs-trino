# 官方 elasticsearch 源码（vendored）与补丁清单

`fs_elasticsearch` 的主体是 **官方 483 tag 的 `plugin/trino-elasticsearch` 源码**，原样收在本仓库里，只在 keyword 子字段（multi-field）这部分打了 4 处补丁。官方功能、配置项、隐藏列、类型映射、谓词下推全部保持原样。

| 项 | 值 |
| --- | --- |
| 上游 | <https://github.com/trinodb/trino/tree/483/plugin/trino-elasticsearch>（Apache License 2.0，文件头原样保留） |
| 上游文件 | 52 个（`src/main/java`，不含官方测试代码） |
| 本仓库新增 | 2 个：`FsElasticsearchConnectorFactory`、`FsElasticsearchPlugin` |
| 落点 | `src/main/java/io/trino/plugin/elasticsearch/` |
| 与上游不一致的文件 | 4 个，全部带 `// PATCH(fs-trino)` 注释 |
| 校验 | `sha256sum -c docs/vendored-elasticsearch.sha256`（48 个未改动文件逐个与上游比 sha256） |
| 编译依赖 | 全部来自 Maven Central，版本按官方 483 的 `pom.xml` 锁定，见 `build.gradle` |

构建不需要 docker、不需要官方镜像。

## 为什么 vendored，而不是依赖官方发布的 jar

483 的 `io.trino:trino-elasticsearch` **没有发布到 Maven Central**（该坐标只发布到 476，可对比 <https://repo1.maven.org/maven2/io/trino/trino-elasticsearch/>），拿不到编译产物。

更关键的是：vendored 之后补丁类和官方类在同一个 package、同一个 classloader 里，可以直接用包私有成员。这是补丁能压到 4 个文件的原因——例如 `IndexMetadata` 的内部类型（`PrimitiveType` / `ScaledFloatType` / `ObjectType`）和 `ScanQueryPageSource` 的解码流程本来都是包私有、public API 里看不见的。若改成在外部依赖的官方 jar 上做包装，只能用 public API，改动面会大得多，正是要避免的。

## 补丁清单

只有这 4 个文件与上游不同。`grep -rn 'PATCH(fs-trino)' src/main/java` 可以一次看全。

### 1. `client/IndexMetadata.java` — 多一个列类型标记

`Type` 的 `@JsonSubTypes` 里登记 `multi_field`，并新增空的 `record MultiFieldType()`。

列句柄要在 coordinator 序列化、在 worker 反序列化，所以多出来的列类型必须能过 Jackson。走 `@JsonSubTypes` 而不是自定义 serializer，是为了不动上游的序列化路径。

### 2. `client/ElasticsearchClient.java` — 从 mapping 注册子字段列（`parseType`）

父字段解析完之后，遍历它 `fields` 块里的每个子字段：

- **只注册 `type=keyword` 的子字段**。keyword 一定有 doc value，可以用 `docvalue_fields` 取回来；text 等子字段没有 doc value，注册了也读不出值，直接忽略并打 debug 日志。
- 列名是子字段在 Elasticsearch 里的真实路径：`父字段名.子字段名`（mapping 里就是 `name.keyword` 这个键）。
- `isArray` 默认**继承父字段**（multi-field 存的是父字段值的副本，父字段是数组它也是数组），可用 `_meta.trino["父字段.子字段"].isArray` 单独覆盖。
- `object` / `nested` 下的子字段不处理——那是对象内部字段，不是 multi-field。

### 3. `ElasticsearchMetadata.java` — 子字段列的类型与谓词下推

- `MultiFieldType` → `varchar` + `VarcharDecoder`（走 `document.fields()`，见补丁 4）。
- `supportsPredicates`：keyword 子字段与 keyword 字段同等对待。Elasticsearch 接受点号路径做 `term` / `range` / `regexp` 查询，所以 `WHERE "name.keyword" = 'x'` 依然会被下推成 ES 查询，而不是拿回来在 Trino 里过滤。

### 4. `ScanQueryPageSource.java` — 从 `fields` 取值，而不是 `_source`

- 子字段列全部加进 `documentFields`（→ 请求体里的 `docvalue_fields`）。
- 从 `requiredFields`（→ 请求体里的 `_source.includes`）里**排除**子字段列：它们不在 `_source` 里，留着会白取甚至报错。
- 取值：命中的 `fields` 里是数组，数组列给整个 list，标量列取第一个；没有值时给 `NULL`。

## 与官方一致的部分（没有改）

配置项（`elasticsearch.*` 全部属性）、隐藏列 `_id` / `_source` / `_score`、类型映射、谓词下推、按 shard 并行读、`count(*)` 走 count API、`raw_query` 表函数、`nodes` 系统表、`_meta.trino` 的 `isArray` / `asRawJson`、认证方式（`PASSWORD` / `AWS` / keystore / truststore）。

## 怎么确认"只有这 4 处"

```bash
grep -rn 'PATCH(fs-trino)' src/main/java
sha256sum -c docs/vendored-elasticsearch.sha256    # 未改动的 48 个文件必须全是 OK
git diff --stat <上一次同步上游的 commit> -- src/main/java/io/trino/plugin/elasticsearch
```

## 升级 Trino 版本

1. 取新 tag 的官方源码，覆盖 `src/main/java/io/trino/plugin/elasticsearch/`（保留本仓库新增的 `FsElasticsearchConnectorFactory` / `FsElasticsearchPlugin`，以及 4 处 `PATCH(fs-trino)`）。源码可整份 clone 到本地，也可以按文件从 `https://raw.githubusercontent.com/trinodb/trino/<tag>/plugin/trino-elasticsearch/src/main/java/io/trino/plugin/elasticsearch/<相对路径>` 取。
2. 对照上面的补丁清单，确认 4 处的上下文没有漂移（官方若重构过 `parseType` / `ScanQueryPageSource`，补丁要跟着改）。
3. 同步 `build.gradle` 里 ES rest-client、AWS SDK、airlift 的版本——新 tag 的 `plugin/trino-elasticsearch/pom.xml` 里锁着这些版本号。
4. 重新生成校验清单：`docs/vendored-elasticsearch.sha256`（换 tag 后 sha256 全变），生成方式见文件头注释。
5. 跑验收：`gradle clean build`，再按 README 的验收 SQL 在本地 Trino 上过一遍（重点跑 keyword 子字段列的查询与 `DESCRIBE`）。
