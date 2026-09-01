/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public final class HttpClient
{
    private static final String TABLES_PATH = "/integration/trino/tables";
    private static final String DATA_PATH = "/integration/trino/data";

    private final HttpConfig config;
    private final ObjectMapper objectMapper;
    private final TypeManager typeManager;
    private final java.net.http.HttpClient httpClient;
    private final Supplier<Map<String, Map<String, HttpTable>>> schemas;

    @Inject
    public HttpClient(HttpConfig config, TypeManager typeManager)
    {
        this.config = requireNonNull(config, "config is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.objectMapper = new ObjectMapper();
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        Supplier<Map<String, Map<String, HttpTable>>> supplier = () -> {
            try {
                return loadTables();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        if (config.getMetadataCacheTtlSeconds() <= 0) {
            schemas = Suppliers.memoize(supplier);
        }
        else {
            schemas = Suppliers.memoizeWithExpiration(
                    supplier,
                    config.getMetadataCacheTtlSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    public Set<String> getSchemaNames()
    {
        return schemas.get().keySet();
    }

    public Set<String> getTableNames(String schemaName)
    {
        requireNonNull(schemaName, "schemaName is null");
        Map<String, HttpTable> tables = schemas.get().get(schemaName);
        if (tables == null) {
            return ImmutableSet.of();
        }
        return tables.keySet();
    }

    public HttpTable getTable(String schemaName, String tableName)
    {
        requireNonNull(schemaName, "schemaName is null");
        requireNonNull(tableName, "tableName is null");
        Map<String, HttpTable> tables = schemas.get().get(schemaName);
        if (tables == null) {
            return null;
        }
        return tables.get(tableName);
    }

    public HttpDataPage fetchPage(String schemaName, String tableName, long offset, int limit)
            throws IOException
    {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("catalog", config.getCatalog());
        body.put("schema", schemaName);
        body.put("table", tableName);
        body.put("offset", offset);
        body.put("limit", limit);

        JsonNode data = postJson(DATA_PATH, body);
        List<JsonNode> rows = rowsFrom(data);
        OptionalLong nextOffset = parseNextOffset(data, offset, rows.size(), limit);
        return new HttpDataPage(rows, nextOffset);
    }

    private Map<String, Map<String, HttpTable>> loadTables()
            throws IOException
    {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("catalog", config.getCatalog());
        JsonNode data = postJson(TABLES_PATH, body);
        JsonNode schemaNodes = data.path("schemas");

        Map<String, Map<String, HttpTable>> result = new LinkedHashMap<>();
        if (schemaNodes == null || !schemaNodes.isArray()) {
            return ImmutableMap.of();
        }

        for (JsonNode schemaNode : schemaNodes) {
            String schemaName = schemaNode.path("name").asText("");
            if (schemaName.isEmpty()) {
                continue;
            }

            Map<String, HttpTable> schemaTables = new LinkedHashMap<>();
            JsonNode tableNodes = schemaNode.path("tables");
            if (tableNodes != null && tableNodes.isArray()) {
                for (JsonNode tableNode : tableNodes) {
                    HttpTable table = parseTable(tableNode);
                    if (table != null) {
                        schemaTables.put(table.getName(), table);
                    }
                }
            }
            result.put(schemaName, ImmutableMap.copyOf(schemaTables));
        }
        return ImmutableMap.copyOf(result);
    }

    private HttpTable parseTable(JsonNode tableNode)
    {
        String name = tableNode.path("name").asText("");
        if (name.isEmpty()) {
            return null;
        }

        ImmutableList.Builder<HttpColumn> columns = ImmutableList.builder();
        JsonNode columnNodes = tableNode.path("columns");
        if (columnNodes != null && columnNodes.isArray()) {
            for (JsonNode columnNode : columnNodes) {
                String columnName = columnNode.path("name").asText("");
                String typeName = columnNode.path("type").asText("");
                if (columnName.isEmpty() || typeName.isEmpty()) {
                    continue;
                }
                Type type;
                try {
                    type = typeManager.fromSqlType(typeName);
                }
                catch (RuntimeException e) {
                    throw new IllegalArgumentException("Invalid column type '" + typeName + "' for column '" + columnName + "'", e);
                }
                columns.add(new HttpColumn(columnName, type));
            }
        }
        return new HttpTable(name, columns.build());
    }

    private static List<JsonNode> rowsFrom(JsonNode data)
    {
        JsonNode rows = data.path("rows");
        if (rows == null || !rows.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            result.add(row);
        }
        return result;
    }

    private static OptionalLong parseNextOffset(JsonNode data, long currentOffset, int returnedRows, int limit)
    {
        boolean hasMore = data.path("hasMore").asBoolean(returnedRows == limit);
        JsonNode nextOffset = data.get("nextOffset");
        if (nextOffset == null) {
            nextOffset = data.get("next");
        }
        if (nextOffset != null && !nextOffset.isNull()) {
            long value;
            if (nextOffset.isIntegralNumber()) {
                value = nextOffset.asLong();
            }
            else {
                try {
                    value = Long.parseLong(nextOffset.asText());
                }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid next offset: " + nextOffset, e);
                }
            }
            return hasMore ? OptionalLong.of(value) : OptionalLong.empty();
        }

        if (!hasMore || returnedRows == 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(currentOffset + returnedRows);
    }

    private JsonNode postJson(String path, ObjectNode body)
            throws IOException
    {
        URI uri = resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (!config.getApiKey().isEmpty()) {
            builder.header(config.getApiKeyHeader(), config.getApiKey());
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        ensureSuccess(response);

        JsonNode root = objectMapper.readTree(response.body());
        if (root == null || root.path("code").asInt() != 0) {
            String message = root == null ? "" : root.path("message").asText(response.body());
            throw new IOException("HTTP integration API returned an error: " + message);
        }
        JsonNode data = root.get("data");
        return data == null || data.isNull() ? objectMapper.createObjectNode() : data;
    }

    private URI resolve(String path)
    {
        String base = config.getBaseUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private HttpResponse<String> send(HttpRequest request)
            throws IOException
    {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for HTTP response", e);
        }
    }

    private static void ensureSuccess(HttpResponse<String> response)
            throws IOException
    {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP request failed with status " + status + ": " + response.body());
        }
    }
}
