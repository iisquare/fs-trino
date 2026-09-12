# fs-trino

`fs-trino` 是一个 Trino 插件，内置两个连接器：

- `fs_trino`：通用 HTTP 数据源，只负责通过 HTTP 调用服务端注册好的 schema、table 和行数据，不感知业务系统的内部表结构、字段映射或数据来源。
- `fs_elasticsearch`：直连 Elasticsearch，把索引读成表，并把 keyword 等子字段注册为独立列。

## 技术栈

- Trino：483
- JDK：25
- Gradle：9.x
- 根项目名：`fs-trino`

## 目录说明

- `src/main/java/io/trino/plugin/http`：`fs_trino` 连接器源码。
- `src/main/java/io/trino/plugin/elasticsearch`：`fs_elasticsearch` 连接器源码。
- `src/main/resources/META-INF/services/io.trino.spi.Plugin`：插件 SPI 入口。
- `build.gradle`：插件构建配置。
- `settings.gradle`：Gradle 项目配置，`rootProject.name = 'fs-trino'`。

## 构建

在 `fs-trino` 项目根目录执行：

```bash
GRADLE_USER_HOME=/tmp/gradle-home \
  /mnt/d/openservices/gradle-9.6.0/bin/gradle clean build --no-daemon
```

构建产物：

- `build/libs/fs-trino-483.jar`
- `build/fs-trino-483.zip`：Trino 插件部署包

## 部署 Trino 插件

1. 确保已经构建出插件包：

```bash
ls -lh build/fs-trino-483.zip
```

2. 将插件包解压到 Trino 插件目录：

```bash
mkdir -p "$TRINO_HOME/plugin/fs-trino"
unzip build/fs-trino-483.zip -d "$TRINO_HOME/plugin/fs-trino"
```

3. 创建 catalog 配置文件。文件名就是 Trino 中的 catalog 名称。建议使用小写字母、数字和下划线：

```bash
cat > "$TRINO_HOME/etc/catalog/fs_bi.properties" <<'EOF'
connector.name=fs_trino
base-uri=http://127.0.0.1:7815
api-key=change-me
api-key-header=x-api-key
http.page-size=500
metadata-cache-ttl-seconds=60
EOF
```

4. 重启或启动 Trino 服务。

5. 验证 catalog：

```sql
SHOW CATALOGS;
SHOW SCHEMAS FROM fs_bi;
```

## Trino catalog 配置项

| 配置 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `base-uri` | 是 | - | 服务端基础地址，例如 `http://127.0.0.1:7815` |
| `api-key` | 否 | 空 | API Key，为空时不发送认证头 |
| `api-key-header` | 否 | `x-api-key` | API Key 请求头名称 |
| `http.page-size` | 否 | `500` | 每次读取的最大行数 |
| `http.connect-timeout-seconds` | 否 | `10` | 连接超时秒数 |
| `http.request-timeout-seconds` | 否 | `60` | 请求超时秒数 |
| `metadata-cache-ttl-seconds` | 否 | `60` | schema/table 元数据缓存秒数，0 表示永久缓存 |

catalog 名称不在配置文件中填写，也不在 BI 服务端配置。它由 Trino 加载 catalog 文件时自动确定。例如：

```text
$TRINO_HOME/etc/catalog/fs_bi.properties -> catalog 名称为 fs_bi
```

不建议在 catalog 文件名中使用中划线 `-`。虽然可以写成 `fs-bi.properties`，但 catalog 名称会包含 `-`，在 SQL 中必须使用双引号转义，例如 `SHOW SCHEMAS FROM "fs-bi"`，否则会报语法错误。使用 `fs_bi` 这类下划线名称可以在 SQL 中直接书写。

### 关于 connector.name 中划线转下划线

本插件的 `ConnectorFactory.getName()` 注册为 `fs_trino`。推荐在 catalog 中直接使用：

```properties
connector.name=fs_trino
```

也可以写成：

```properties
connector.name=fs-trino
```

原因是 Trino 在加载 catalog 时，会把 `connector.name` 中的中划线 `-` 按兼容逻辑转换为下划线 `_`，再用转换后的名称去匹配插件工厂。因此只要插件工厂注册的是 `fs_trino`，即使 catalog 写成 `fs-trino`，Trino 也会自动转换并正常匹配运行。

不过使用 `connector.name=fs-trino` 时，Trino 会记录一条类似 `deprecated connector name` 的警告日志。为避免告警和歧义，推荐直接写 `connector.name=fs_trino`。

因此这里统一使用下划线形式的连接器名 `fs_trino`。项目名、插件目录名以及 jar/zip 产物名仍保持 `fs-trino`，与 `connector.name` 是两个不同的概念。

插件会把该 catalog 名称发送给服务端，服务端只负责注册 schema 和 table。

## 服务端接口契约

插件固定调用以下两个接口：

```text
POST {base-uri}/integration/trino/tables
POST {base-uri}/integration/trino/data
```

两个接口均为 JSON POST，并携带 `api-key-header` 指定的认证头。

### 注册表结构

请求：

```json
{
  "catalog": "fs_bi"
}
```

响应：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "schemas": [
      {
        "name": "api",
        "tables": [
          {
            "name": "user_list",
            "columns": [
              {"name": "id", "type": "bigint"},
              {"name": "name", "type": "varchar"}
            ]
          }
        ]
      }
    ]
  }
}
```

`schemas` 和 `tables` 完全由服务端决定，插件不内置任何 schema 或 table 名称。

### 读取数据

请求：

```json
{
  "catalog": "fs_bi",
  "schema": "api",
  "table": "user_list",
  "offset": 0,
  "limit": 500
}
```

响应：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "rows": [
      {"id": 1, "name": "Alice"},
      {"id": 2, "name": "Bob"}
    ],
    "total": 2,
    "hasMore": false,
    "nextOffset": null
  }
}
```

如果还有下一页：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "rows": [],
    "total": 1200,
    "hasMore": true,
    "nextOffset": 500
  }
}
```

## fs-java BI 模块部署

`fs-java` 的 `web/bi` 模块负责实现上述服务端接口。

### 已实现内容

- `TrinoIntegrationController`：提供 `/integration/trino/tables` 和 `/integration/trino/data`。
- `TrinoIntegrationService`：注册 schema/table，读取 `DataApi` 和 `DataExcel` 数据，并在方法入口校验 `x-api-key`。
- `DataApiDao.findByName`：按 `DataApi.name` 查询。

### 配置

编辑 `fs-java/web/bi/src/main/resources/application-dev.yml`：

```yaml
fs:
  bi:
    trino:
      api-key: change-me
      url: jdbc:trino://127.0.0.1:8443
      user: root
      password: admin888
```

### 编译和启动

编译：

```bash
JAVA_HOME=/opt/jdk-17.0.12 GRADLE_USER_HOME=/tmp/gradle-home \
  /mnt/d/openservices/gradle-7.6.5/bin/gradle :web:bi:compileJava --no-daemon
```

启动 BI 服务后，确保服务端口与 Trino catalog 中 `base-uri` 一致。

## 调试

### IDEA 远程调试插件

插件运行在 Trino Server 的 JVM 中，不直接作为独立进程启动，因此 IDEA 使用 Remote JVM Debug 附加到 Trino 进程进行调试。

1. 用 IDEA 打开 `fs-trino` 项目，Project SDK 和 Gradle JVM 均选择 JDK 25。

2. 构建并部署插件：

```bash
GRADLE_USER_HOME=/tmp/gradle-home \
  /mnt/d/openservices/gradle-9.6.0/bin/gradle clean build --no-daemon

mkdir -p "$TRINO_HOME/plugin/fs-trino"
unzip build/fs-trino-483.zip -d "$TRINO_HOME/plugin/fs-trino"
```

如果使用 `fs-docker`，将 `build/fs-trino-483.zip` 同步到：

```text
fs-docker/service/trino/plugins/fs-trino-483.zip
```

然后重新构建并启动 Trino 容器。

3. 启动 Trino 时开启 JDWP 调试端口。本地发行版：

```bash
export JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
"$TRINO_HOME/bin/launcher" run
```

需要在插件加载阶段命中断点时，可将 `suspend=n` 改为 `suspend=y`。

Docker 方式可在 `trino` 服务中临时增加：

```yaml
environment:
  - JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
ports:
  - "5005:5005"
```

4. IDEA 中创建 `Remote JVM Debug` 配置：

```text
Host: localhost
Port: 5005
Transport: Socket
Use module classpath: fs-trino.main
```

5. 在以下位置打断点：

```text
HttpPlugin
HttpConnectorFactory.create()
HttpMetadata.listSchemaNames()
HttpMetadata.getTableHandle()
HttpMetadata.getColumnHandles()
HttpClient.loadTables()
HttpClient.fetchPage()
HttpRecordCursor.advanceNextPosition()
```

6. 启动 Debug 后，在 Trino 客户端执行：

```sql
SHOW SCHEMAS FROM fs_bi;
SHOW TABLES FROM fs_bi.excel;
SELECT * FROM fs_bi.excel."表名" LIMIT 10;
```

修改插件代码后，需要重新执行 `clean build`、重新部署插件包并重启 Trino，再重新 attach。

### 1. 检查服务端接口

```bash
curl -sS -X POST http://127.0.0.1:7815/integration/trino/tables \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: change-me' \
  -d '{"catalog":"fs_bi"}'
```

检查返回的 `schemas` 和 `columns`。

### 2. 检查数据接口

```bash
curl -sS -X POST http://127.0.0.1:7815/integration/trino/data \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: change-me' \
  -d '{"catalog":"fs_bi","schema":"excel","table":"订单表","offset":0,"limit":10}'
```

### 3. 在 Trino 中查询

```sql
SHOW SCHEMAS FROM fs_bi;
SHOW TABLES FROM fs_bi.api;
SHOW TABLES FROM fs_bi.excel;
SELECT * FROM fs_bi.api.user_list LIMIT 10;
```

## 排查

- `Invalid API key`：检查 Trino catalog 中的 `api-key` 和 BI 服务端 `fs.bi.trino.api-key` 是否一致。
- `HTTP integration API returned an error`：查看 BI 服务日志中 `/integration/trino` 请求异常。
- schema/table 没有更新：调整 `metadata-cache-ttl-seconds`，或重启 Trino 节点。
- `base-uri` 无法访问：确认 BI 服务已启动，并且 Trino 所在机器可以访问该地址。

# fs_elasticsearch 连接器

`fs_elasticsearch` 由插件直连 Elasticsearch，通过 `GET /_mapping` 读取索引结构、通过 `POST /_search` 读取数据，不经过平台服务端。

每个 ES 索引对应一张表，全部归入一个可配置的 schema（默认 `es`）。索引的 mapping 字段全部注册为列，**`fields` 中的子字段（multi-field）也会注册为独立列**：`text` 类型字段 `name` 带有 `"fields": {"keyword": {"type": "keyword"}}` 时，会同时产生 `name` 和 `name.keyword` 两列。

## Elasticsearch catalog 配置

```bash
cat > "$TRINO_HOME/etc/catalog/fs_es.properties" <<'EOF'
connector.name=fs_elasticsearch
elasticsearch.uri=http://127.0.0.1:9200
elasticsearch.username=elastic
elasticsearch.password=admin888
elasticsearch.default-schema-name=es
EOF
```

| 配置 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `elasticsearch.uri` | 是 | - | Elasticsearch 基础地址，例如 `http://127.0.0.1:9200` |
| `elasticsearch.username` | 否 | 空 | Basic 认证用户名，为空时不发送认证头 |
| `elasticsearch.password` | 否 | 空 | Basic 认证密码 |
| `elasticsearch.tls.verify` | 否 | `true` | `false` 表示信任任意证书并跳过主机名校验，用于自签名证书 |
| `elasticsearch.default-schema-name` | 否 | `es` | 所有索引作为表归入的 schema 名称 |
| `elasticsearch.page-size` | 否 | `500` | 每次读取的最大行数 |
| `elasticsearch.connect-timeout-seconds` | 否 | `10` | 连接超时秒数 |
| `elasticsearch.request-timeout-seconds` | 否 | `60` | 请求超时秒数 |
| `elasticsearch.metadata-cache-ttl-seconds` | 否 | `60` | 索引结构缓存秒数，0 表示永久缓存 |
| `elasticsearch.pit-keep-alive-seconds` | 否 | `60` | point in time / scroll 的 keep alive，每页刷新 |

## 索引结构与列

- 索引即表；`.` 开头的系统索引（`.kibana`、`.security`、`.ds-*` 等）不注册为表。
- 没有任何 mapped 字段的索引不注册为表。
- 映射为 `object`、`nested`、`flattened`、`geo_point`、`geo_shape` 的字段是 `json` 类型列，可用 `json_extract` 等 JSON 函数处理。
- 子字段（multi-field）永远是**独立列**，列名为 `父字段.子字段`，取子字段自己的类型。因为名字里带点号，在 SQL 中必须用双引号：

```sql
SHOW SCHEMAS FROM fs_es;
SHOW TABLES FROM fs_es.es;
DESCRIBE fs_es.es.demo_products;

SELECT name, "name.keyword" FROM fs_es.es.demo_products LIMIT 10;
SELECT name FROM fs_es.es.demo_products WHERE "name.keyword" = 'Alice';
SELECT "name.keyword", COUNT(*) FROM fs_es.es.demo_products GROUP BY "name.keyword";
```

子字段的值不在 `_source` 中，插件通过 `_search` 的 `fields` 参数读取。`ignore_above` 等导致字段未索引时该列为 `NULL`；字段有多个值时只取第一个。

## 类型映射

| Elasticsearch 类型 | Trino 类型 |
| --- | --- |
| `keyword`、`text`、`wildcard`、`constant_keyword`、`match_only_text`、`search_as_you_type`、`ip` | `varchar` |
| `long` | `bigint` |
| `integer` | `integer` |
| `short` | `smallint` |
| `byte` | `tinyint` |
| `double`、`scaled_float` | `double` |
| `float`、`half_float` | `real` |
| `unsigned_long` | `decimal(20, 0)` |
| `boolean` | `boolean` |
| `date` | `timestamp(3)` |
| `date_nanos` | `timestamp(6)` |
| `binary` | `varbinary` |
| `object`、`nested`、`flattened`、`geo_point`、`geo_shape` | `json` |

其他类型（`alias`、`join`、`percolator`、`completion`、`dense_vector` 等）不会注册为列，索引中其余字段仍可正常查询。

日期字段：`_source` 中为 ISO 字符串时按时间解析，为数值时按 epoch 毫秒解析（`date_nanos` 按 epoch 纳秒）。

## 读取与分页

- 每个索引一个 split，读取时用 point in time (PIT) + `search_after` 分页，保证翻页期间数据视图稳定；不支持 PIT 的集群自动降级为 scroll。
- 查询结束或提前终止（如 `LIMIT`）时释放 PIT/scroll；进程被强杀时由 Elasticsearch 按 keep alive 自动过期。
- 没有谓词下推：`WHERE`、`ORDER BY`、`GROUP BY` 由 Trino 在读取到的数据之上完成，每次查询都是全量分页扫描，可通过 `elasticsearch.page-size` 权衡单页行数与请求次数。

## 支持的 Elasticsearch 版本

`fields` 搜索参数要求 Elasticsearch 7.10 及以上，因此支持范围为 7.10 - 8.x，6.x 及以下不支持。

## fs_elasticsearch 排查

- `Elasticsearch request to ... failed with status 401`：检查 `elasticsearch.username` / `elasticsearch.password`。
- `Elasticsearch request to ... failed with status 404`：索引已被删除或未刷新缓存，调整 `elasticsearch.metadata-cache-ttl-seconds` 或重启 Trino 节点。
- 自签名证书报 SSL 错误：设置 `elasticsearch.tls.verify=false`。
- 新增索引或新增字段后查询不到：调整 `elasticsearch.metadata-cache-ttl-seconds`，或重启 Trino 节点。
- 子字段列为 `NULL`：确认该字段确实定义了 `fields` 子字段，且值未被 `ignore_above` 截断。
