# fs-trino

`fs-trino` 是一个 Trino 插件，内置两个连接器：

- `fs_http`：通用 HTTP 数据源，只负责通过 HTTP 调用服务端注册好的 schema、table 和行数据，不感知业务系统的内部表结构、字段映射或数据来源。
- `fs_elasticsearch`：直连 Elasticsearch，把索引读成表，支持 keyword 等子字段和 nested 对象数组。

## 技术栈

- Trino：483
- JDK：25
- Gradle：9.x
- 根项目名：`fs-trino`

## 目录说明

- `src/main/java/io/trino/plugin/http`：`fs_http` 连接器源码。
- `src/main/java/io/trino/plugin/elasticsearch`：`fs_elasticsearch` 连接器源码。
- `src/main/resources/META-INF/services/io.trino.spi.Plugin`：插件 SPI 入口。
- `build.gradle`：插件构建配置。
- `settings.gradle`：Gradle 项目配置，`rootProject.name = 'fs-trino'`。
- `docs/vendored-elasticsearch.md`：vendored 官方源码的补丁清单、与上游同步的步骤。
- `docs/vendored-elasticsearch.sha256`：未改动的官方文件校验值，`sha256sum -c` 可校验。
- `libs/official-es`：**可选**，从官方镜像抽出的官方 `elasticsearch` 连接器 jar，只用于与官方对拍，不参与构建（已在 `.gitignore` 中忽略）。

## fs_elasticsearch 的实现方式：vendored 官方源码

`fs_elasticsearch` **不是另写一套实现**：官方 483 tag 的 `plugin/trino-elasticsearch` 源码原样收在本仓库里，只在 keyword 子字段（multi-field）、text 字段 `LIKE '%...%'` 短语下推、nested 标量与数组映射及 nested 谓词下推这几部分打了补丁，共涉及 9 个官方文件。其余官方功能、配置项、隐藏列和类型映射保持原样。

- 官方源码 52 个文件 + 本仓库新增 4 个（`FsElasticsearchConnectorFactory`、`FsElasticsearchPlugin`、`ElasticsearchNestedPredicateTranslator`、`ElasticsearchNestedQuery`），都在 `src/main/java/io/trino/plugin/elasticsearch/`。
- 与上游不一致的只有 9 个官方文件，每处都有 `// PATCH(fs-trino)` 注释：`grep -rn 'PATCH(fs-trino)' src/main/java` 一次看全。
- 43 个未改动文件与上游逐字节一致：`sha256sum -c docs/vendored-elasticsearch.sha256`。
- 补丁内容、升级上游的步骤见 [docs/vendored-elasticsearch.md](docs/vendored-elasticsearch.md)。

**构建不需要 docker、也不需要官方镜像**：全部依赖来自 Maven Central，版本按官方 483 的 `pom.xml` 锁定（见 `build.gradle`）。

为什么用 vendored 源码而不是官方发布的 jar：483 的 `io.trino:trino-elasticsearch` 没有发布到 Maven Central（该坐标只到 476，可对比 <https://repo1.maven.org/maven2/io/trino/trino-elasticsearch/>），拿不到编译产物；而且 vendored 之后补丁类和官方类同包、同 classloader，能直接用包私有成员，补丁面才压得住（见 `docs/vendored-elasticsearch.md`）。

### 与官方镜像对拍（可选）

只有需要**和镜像里的官方连接器做 A/B 对比**时才用这一节，构建不需要它。

镜像 fs-docker 里已经在用（它的 trino 服务就是 `FROM trinodb/trino:${TRINO_VERSION}`），正常情况下本机已有：

```bash
docker images trinodb/trino:483     # 没有的话先 docker pull trinodb/trino:483
```

复制整个插件目录（`docker create` + `docker cp` 在 Git Bash / WSL / macOS / Linux 下都可用）：

```bash
docker create --name trino483 trinodb/trino:483
rm -rf libs/official-es
mkdir -p libs/official-es
docker cp trino483:/usr/lib/trino/plugin/elasticsearch/. libs/official-es/
docker rm trino483
```

一次性容器也可以（Git Bash 下要把宿主路径写成 `D:/...`，否则 MSYS 会把 `/d/...` 转换掉）：

```bash
docker run --rm -v "$(cygpath -m "$PWD")/libs:/out" trinodb/trino:483 \
  sh -c 'cp -a /usr/lib/trino/plugin/elasticsearch/. /out/official-es/'
```

不想用 `docker cp` 也可以走任何能把镜像里 `/usr/lib/trino/plugin/elasticsearch` 取出来的办法（例如 `docker save` 后解层），最终 `libs/official-es/` 里是那一堆 jar 即可。

### 校验抽取结果

```bash
ls libs/official-es | wc -l    # jar 数量（几十个：官方连接器本体 + 它的依赖）
ls libs/official-es | head     # 应能看到 trino-elasticsearch-483.jar 以及各类依赖 jar
```

再确认连接器主 jar 和 SPI 入口都在（不关心具体是哪个 jar）：

```bash
for j in libs/official-es/*.jar; do
  unzip -l "$j" | grep -q 'io/trino/plugin/elasticsearch/ElasticsearchMetadata.class' && echo "连接器主 jar: $j"
done
for j in libs/official-es/*.jar; do
  unzip -p "$j" META-INF/services/io.trino.spi.Plugin 2>/dev/null | grep -q ElasticsearchPlugin && echo "SPI 入口: $j"
done
```

两条 `echo` 都有输出就算抽取成功。把这一堆 jar 放进一个独立的插件目录（例如 `$TRINO_HOME/plugin/official-es/`），再配一个 `connector.name=elasticsearch` 的 catalog，就能和 `fs_es` 并排跑同一条 SQL 对比结果（`fs_es` 注册的是 `fs_elasticsearch`，两者互不冲突）。

### 升级 Trino 版本时

1. 按 [docs/vendored-elasticsearch.md](docs/vendored-elasticsearch.md) 同步官方源码、打回 9 个文件里的补丁、更新 `build.gradle` 里的依赖版本；
2. 如果还要做对拍，`rm -rf libs/official-es` 并按本节重新抽取。

### 许可

vendored 的官方源码是 Apache License 2.0，文件头原样保留，编译进 `build/libs/fs-trino-483.jar` 一起分发。`build/fs-trino-483.zip` 里还会有 `runtimeClasspath` 上的第三方依赖 jar（guava、guice、airlift、Elasticsearch rest-client、AWS SDK 等），各自带自己的 `META-INF`，打包时不要剔除或改写。

## 构建

前置条件：无。依赖全部来自 Maven Central，不需要 docker，也不需要 `libs/official-es/`。

在 `fs-trino` 项目根目录执行：

```bash
GRADLE_USER_HOME=/tmp/gradle-home \
  /mnt/d/openservices/gradle-9.6.0/bin/gradle clean build --no-daemon
```

构建产物：

- `build/libs/fs-trino-483.jar`：插件 jar，含 `fs_http` 与 vendored 的官方 elasticsearch 实现（+9 个文件的补丁）及两个插件入口。
- `build/fs-trino-483.zip`：Trino 插件部署包，含插件 jar 及运行期依赖（Elasticsearch rest-client、AWS SDK、guava、guice、airlift 等），解到插件目录即可用。

编译参数**不要删**：`build.gradle` 里 `compileJava` 带 `-parameters`，和官方 Maven 构建一致。SPI 里大量 `@JsonCreator` 构造器的参数上没有 `@JsonProperty`（例如 `VarcharDecoder.Descriptor(String path)`），Jackson 只能靠 class 文件里的参数名反序列化；少了它，coordinator 发出去的列句柄在 worker 侧构造不出来，查询会以完全不相干的现象失败：

```text
Query failed: Unexpected response from https://<node>:8443/v1/task/<queryId>.0.0.0?summarize
Caused by: Cannot construct instance of `io.trino.plugin.elasticsearch.decoders.VarcharDecoder$Descriptor`
           (although at least one Creator exists): cannot deserialize from Object value
           (no delegate- or property-based Creator)
```

排查手段：`javap -v -p -cp build/libs/fs-trino-483.jar 'io.trino.plugin.elasticsearch.decoders.VarcharDecoder$Descriptor' | grep -A3 MethodParameters`，应当打印出参数名 `path`。

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
connector.name=fs_http
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

## fs_http catalog 配置项

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

本插件的 `ConnectorFactory.getName()` 注册为 `fs_http`。推荐在 catalog 中直接使用：

```properties
connector.name=fs_http
```

也可以写成：

```properties
connector.name=fs-http
```

原因是 Trino 在加载 catalog 时，会把 `connector.name` 中的中划线 `-` 按兼容逻辑转换为下划线 `_`，再用转换后的名称去匹配插件工厂。因此只要插件工厂注册的是 `fs_http`，即使 catalog 写成 `fs-http`，Trino 也会自动转换并正常匹配运行。

不过使用 `connector.name=fs-http` 时，Trino 会记录一条类似 `deprecated connector name` 的警告日志。为避免告警和歧义，推荐直接写 `connector.name=fs_http`。

因此这里统一使用下划线形式的连接器名 `fs_http`。项目名、插件目录名以及 jar/zip 产物名仍保持 `fs-trino`，与 `connector.name` 是两个不同的概念。

连接器名原本是 `fs_trino`，现已更名为 `fs_http`。已有的 catalog 文件必须同步把 `connector.name` 改成 `fs_http`，否则部署新插件后该 catalog 会加载失败。

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

`fs_elasticsearch` 直连 Elasticsearch，用的是**官方 483 连接器的实现 + keyword 子字段、text 字段 LIKE 短语下推、nested 标量/数组映射及 nested 谓词下推补丁**（见 [docs/vendored-elasticsearch.md](docs/vendored-elasticsearch.md)）。配置项、隐藏列、`raw_query` 表函数、`nodes` 系统表都与官方 `elasticsearch` 连接器一致；主要差异是：mapping 里 `fields` 下的 keyword 子字段会注册成独立列，text 字段上的 `LIKE '%...%'` 会下推为 `match_phrase`，nested 谓词会生成正确的 ES `nested` query，数组 nested 使用 `any_match` 过滤。

每个 ES 索引对应一张表，全部归入一个可配置的 schema（默认 `default`）。

因为注册名不同（`fs_elasticsearch` vs 官方 `elasticsearch`），两个连接器可以在同一个 Trino 里并存、各配一个 catalog，方便对拍同一条 SQL。

## Elasticsearch catalog 配置

```properties
# $TRINO_HOME/etc/catalog/fs_es.properties
connector.name=fs_elasticsearch
elasticsearch.host=127.0.0.1
elasticsearch.port=9200
elasticsearch.default-schema-name=default
elasticsearch.security=PASSWORD
elasticsearch.auth.user=elastic
elasticsearch.auth.password=admin888
```

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `elasticsearch.host` | 必填 | 主机名，多个用逗号分隔 |
| `elasticsearch.port` | `9200` | 端口 |
| `elasticsearch.default-schema-name` | `default` | 所有索引作为表归入的 schema |
| `elasticsearch.security` | 无 | `PASSWORD`（Basic 认证）或 `AWS`（请求签名） |
| `elasticsearch.auth.user` / `elasticsearch.auth.password` | 无 | `security=PASSWORD` 时使用 |
| `elasticsearch.aws.access-key` / `aws.secret-key` / `aws.region` / `aws.iam-role` / `aws.external-id` | 无 | `security=AWS` 时使用 |
| `elasticsearch.scroll-size` | `1000` | scroll 每批行数 |
| `elasticsearch.scroll-timeout` | `1m` | scroll 上下文超时 |
| `elasticsearch.request-timeout` | `10s` | 请求超时 |
| `elasticsearch.connect-timeout` | `1s` | 连接超时 |
| `elasticsearch.node-refresh-interval` | `1m` | 可用节点列表刷新间隔 |
| `elasticsearch.max-http-connections` | `25` | HTTP 连接数上限 |
| `elasticsearch.http-thread-count` | CPU 核数 | HTTP 线程数 |
| `elasticsearch.backoff-init-delay` / `backoff-max-delay` / `max-retry-time` | `500ms` / `20s` / `30s` | 反压重试 |
| `elasticsearch.tls.enabled` | `false` | 是否启用 TLS |
| `elasticsearch.tls.keystore-path` / `tls.keystore-password` | 无 | 客户端证书 |
| `elasticsearch.tls.truststore-path` / `tls.truststore-password` | 无 | 信任库 |
| `elasticsearch.tls.verify-hostnames` | `true` | 是否校验主机名 |
| `elasticsearch.ignore-publish-address` | `false` | ES 返回的 `publish_address` 从 Trino 节点不可达时置 `true` |

属性名与官方 483 完全一致，官方文档见 <https://trino.io/docs/current/connector/elasticsearch.html>。**写错的属性名会让 Trino 启动直接报错**（airlift 会报 "Unknown property"），不会被静默忽略；已废弃的属性（如 `elasticsearch.max-hits`、`searchguard.*`）也被显式列出并报错，便于及早发现配置是从老版本搬过来的。

## 索引结构与列

- 索引即表，全部归入 `elasticsearch.default-schema-name` 指定的 schema。
- 没有任何 mapped 字段的索引不注册为表。
- mapping 里的字段全部注册为列；`object` 映射成 Trino 的 `ROW` 类型，可以直接写 `attributes.color`。
- `nested` 默认映射成 `ROW(...)`，可以直接写 `address.city`；过滤时会自动生成 ES `nested` query。
- nested 数组必须通过 `_meta.trino.<字段>.isArray=true` 显式声明，声明后映射成 `ARRAY(ROW(...))`，用 `any_match` 过滤。
- **`fields` 下的 keyword 子字段注册为独立列**（本插件的补丁）：`text` 字段 `name` 带 `"fields": {"keyword": {"type": "keyword"}}` 时，会同时有 `name` 和 `name.keyword` 两列。列名就是子字段在 Elasticsearch 里的路径，因为带点号，SQL 里必须用双引号。
- 只有 `type=keyword` 的子字段注册为列。text 等类型的子字段没有 doc value，取不到值，直接忽略——要把子字段当列用，子字段类型必须是 keyword。
- 子字段列的 `isArray` 默认继承父字段：父字段是数组（`_meta.trino.<字段>.isArray=true`），它的 keyword 子字段列也是数组；需要单独控制时用 `_meta.trino["<父字段>.<子字段>"].isArray` 覆盖。

```sql
SHOW SCHEMAS FROM fs_es;
SHOW TABLES FROM fs_es.default;
DESCRIBE fs_es.default.demo_products;

SELECT name, "name.keyword" FROM fs_es.default.demo_products LIMIT 10;
SELECT name FROM fs_es.default.demo_products WHERE "name.keyword" = 'Alice';
SELECT "name.keyword", COUNT(*) FROM fs_es.default.demo_products GROUP BY "name.keyword";
```

子字段的值不在 `_source` 里，插件通过 `_search` 的 `docvalue_fields` 取（这也是只支持 keyword 子字段的原因：keyword 一定有 doc value）。被 `ignore_above` 截断等导致没有值时该列为 `NULL`；标量列取第一个值，数组列给完整列表。

### 数组列与 JSON 列（`_meta.trino`）

数组要用官方约定的 `_meta.trino` 声明（兼容更老的 `_meta.presto`）：

```json
{
  "mappings": {
    "properties": {
      "tags": {"type": "keyword"},
      "addresses": {
        "type": "nested",
        "properties": {
          "city": {"type": "keyword"}
        }
      },
      "attributes": {"type": "object"}
    },
    "_meta": {
      "trino": {
        "tags": {"isArray": true},
        "addresses": {"isArray": true},
        "attributes": {"asRawJson": true}
      }
    }
  }
}
```

- `isArray: true`：该字段按数组返回（`array(varchar)` 等）。nested 数组也必须显式设置该选项。
- `asRawJson: true`：该列不展开成 `ROW`，直接返回 `_source` 里的 JSON 原文（`varchar`）。

nested 标量直接使用 ROW 字段路径过滤；nested 数组使用 `UNNEST` 展开，或用 `any_match` 保持同一 nested 对象内的条件关联：

```sql
-- 未设置 isArray 的 nested 映射为 row(city varchar, zip integer)
SELECT id
FROM fs_es.default.orders
WHERE address.city = 'Beijing';

-- addresses 设置了 isArray=true，类型为 array(row(city varchar, zip integer))
SELECT id, address.city, address.zip
FROM fs_es.default.orders
CROSS JOIN UNNEST(addresses) AS t(address);

-- 同一条 nested 对象内的 city 和 zip 条件不会跨对象串联
SELECT id
FROM fs_es.default.orders
WHERE any_match(addresses, address -> address.city = 'Beijing' AND address.zip > 100000);
```

标量 nested 的 `=`、`IN`、范围和 LIKE 会包进 ES `nested` query 后下推。数组 nested 的 `any_match` 会下推，并保持同一 nested 对象内的条件关联；当前支持谓词内的 `AND`、`OR`、`=`、`<>` 和大小比较。无法安全翻译的表达式继续留在 Trino 侧，`UNNEST` 后的过滤暂不做额外下推。

`index=false`、keyword `ignore_above`、keyword `normalizer` 等会影响索引值与 `_source` 值一致性的 mapping 会自动关闭对应谓词下推，避免 ES 查询产生假阴性。

### 隐藏列

三个隐藏列不出现在 `DESCRIBE` / `SELECT *` 里，但可以按列名直接查（与官方一致）：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `_id` | `varchar` | 文档 `_id`，用于去重、关联、增量定位 |
| `_source` | `varchar` | 文档 `_source` 原文（JSON 字符串），可用 `json_extract` 解析，也能取到 mapping 里未注册的字段 |
| `_score` | `real` | 相关性得分，配合 `raw_query` 表函数用 |

```sql
SELECT _id, _score, name FROM fs_es.default.demo_products LIMIT 10;
SELECT json_extract("_source", '$.price') FROM fs_es.default.demo_products LIMIT 1;
```

### 表函数与系统表

```sql
-- 直接用原生 ES 查询 DSL（第三个参数是查询体 JSON），列由 query 里指定的字段决定
SELECT * FROM TABLE(fs_es.system.raw_query(
  schema => 'default',
  index => 'demo_products',
  query => '{"query": {"match": {"name": "Alice"}}}'));

-- ES 节点信息
SELECT * FROM fs_es.system.nodes;
```

## 类型映射

映射规则：

| Elasticsearch 类型 | Trino 类型 |
| --- | --- |
| `text`、`keyword` | `varchar` |
| `long` | `bigint` |
| `integer` | `integer` |
| `short` | `smallint` |
| `byte` | `tinyint` |
| `double`、`scaled_float` | `double` |
| `float` | `real` |
| `boolean` | `boolean` |
| `ip` | `ipaddress` |
| `binary` | `varbinary` |
| `date`（没有自定义 `format`） | `timestamp(3)` |
| `object` | `ROW(...)`（`_meta.trino.<字段>.asRawJson=true` 时改为 `varchar` 的 JSON 原文） |
| `nested` | 默认 `ROW(...)`；`_meta.trino.<字段>.isArray=true` 时为 `ARRAY(ROW(...))`；`asRawJson=true` 时为 JSON 原文 `varchar` |
| keyword 子字段（`父字段.keyword`） | `varchar`（本插件的补丁） |

其余类型（`date` 带自定义 `format`、`alias`、`join`、`percolator`、`completion`、`dense_vector`、`flattened`、`date_nanos` 等）不注册为列，索引里其余字段照常可查。

## 读取与分页

- 每个分片一个 split，多分片并行读（用 `preference=_shards:N` 固定到分片）。
- 每个分片用 scroll 分批取，批大小 `elasticsearch.scroll-size`、上下文超时 `elasticsearch.scroll-timeout`，读完释放。
- 谓词下推：`=`、`IN`、范围分别翻译成 ES 的 `term`、`bool(should)`、`range` 查询，只对 keyword（含 keyword 子字段）、整型/浮点、`date`、`boolean` 列生效（`scaled_float` 列不下推）。`LIKE` 对 keyword 列翻译成 `regexp`；对 text 列，只有 `LIKE '%literal%'` 这种单一短语模式会翻译成 `match_phrase`，其他 LIKE 模式仍在 Trino 侧过滤。
- text 字段的 `match_phrase` 使用 ES 字段的 search analyzer，和字符子串语义不完全等价；需要严格短语语义时，由 ES 侧 analyzer 插件配合处理。
- 投影下推：只取用到的列，普通列用 `_source.includes` 裁剪，keyword 子字段列用 `docvalue_fields` 取。
- `count(*)` 走 ES 的 count API，不读文档。

## 支持的 Elasticsearch 版本

跟随官方 483 连接器：官方测试覆盖 7.x 与 8.x。本插件的补丁只用到了 `docvalue_fields`（ES 7 及以上都支持），没有额外的版本限制。

## 与官方 elasticsearch 连接器的差异

`fs_elasticsearch` 就是官方连接器（vendored 源码 + 9 个官方文件的补丁），差异是：mapping 里 `fields` 下的 keyword 子字段会多出独立列（例如 `name.keyword`），text 字段的 `LIKE '%literal%'` 会下推为 `match_phrase`，nested 标量/数组按 `_meta.trino.isArray` 映射，以及 nested 过滤会生成正确的 ES `nested` query。

| | `fs_elasticsearch` | 官方 `elasticsearch` |
| --- | --- | --- |
| keyword 子字段作为独立列（`name.keyword`） | 支持 | 不支持（解析 mapping 时忽略 `fields`，值只从 `_source` 取） |
| text 字段 `LIKE '%literal%'` 下推为 `match_phrase` | 支持 | 不支持（留在 Trino 侧过滤） |
| nested 标量 `ROW(...)` 及直接字段过滤 | 支持 | 能投影为 ROW，但直接点路径谓词不会包 nested query |
| `_meta.trino.isArray=true` 的 nested 数组 | 支持 | 不支持可靠读取多条 nested 对象 |
| `any_match(ARRAY(ROW(...)), row -> ...)` 下推 | 支持 | 不支持 |
| 其余能力（配置项、隐藏列、其他谓词下推、并行读、`count(*)`、`raw_query`、`nodes`、`_meta.trino`、认证方式） | 相同 | 相同 |

剩下的差别只有注册名：本插件注册成 `fs_elasticsearch`，镜像自带的官方连接器注册成 `elasticsearch`，因此同一个 Trino 里两个 catalog 可以并存，用同一条 SQL 对拍（官方那一份的 catalog 属性名、默认值与 `fs_es.properties` 完全一致）：

```properties
# $TRINO_HOME/etc/catalog/es.properties  —— 镜像自带的官方连接器
connector.name=elasticsearch
```

> 官方的 `ElasticsearchPlugin` 也随源码 vendor 进来了，但**故意没有在 `META-INF/services/io.trino.spi.Plugin` 里注册**，避免和镜像自带的官方插件目录抢同名连接器。所以要跑官方那一份，用镜像里的插件目录，不要用我们这包。

## fs_elasticsearch 排查

- `Unknown property 'elasticsearch.xxx'`：属性名写错，或用了老版本的属性名（本插件与官方 483 的属性名完全一致）。
- `Elasticsearch request to ... failed with status 401`：检查 `elasticsearch.security=PASSWORD` 与 `elasticsearch.auth.user` / `auth.password`。
- `Elasticsearch request to ... failed with status 404`：索引被删除，或分片路由过期——节点列表默认每 1 分钟刷新（`elasticsearch.node-refresh-interval`）。
- 自签名证书报 SSL 错误：配 `elasticsearch.tls.truststore-path` / `truststore-password`；仅测试环境可以 `elasticsearch.tls.verify-hostnames=false`。
- 新增索引或字段后查不到：Trino 是按需读 mapping 的（没有长期缓存），先在 ES 侧确认 mapping 已生效，再重试。
- 子字段列全是 `NULL`：确认该子字段的 `type` 是 `keyword`（其它类型的子字段不注册为列），且值没有被 `ignore_above` 截断。
