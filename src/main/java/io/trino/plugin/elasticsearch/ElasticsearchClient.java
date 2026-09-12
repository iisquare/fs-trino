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
package io.trino.plugin.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public final class ElasticsearchClient
{
    private static final Logger log = Logger.get(ElasticsearchClient.class);

    private static final Set<String> JSON_FIELD_TYPES = Set.of(
            "object",
            "nested",
            "flattened",
            "geo_point",
            "geo_shape");

    private static final Map<String, String> SQL_TYPE_BY_FIELD_TYPE = ImmutableMap.<String, String>builder()
            .put("keyword", "varchar")
            .put("text", "varchar")
            .put("wildcard", "varchar")
            .put("constant_keyword", "varchar")
            .put("match_only_text", "varchar")
            .put("search_as_you_type", "varchar")
            .put("ip", "varchar")
            .put("long", "bigint")
            .put("integer", "integer")
            .put("short", "smallint")
            .put("byte", "tinyint")
            .put("double", "double")
            .put("scaled_float", "double")
            .put("float", "real")
            .put("half_float", "real")
            .put("unsigned_long", "decimal(20, 0)")
            .put("boolean", "boolean")
            .put("date", "timestamp(3)")
            .put("date_nanos", "timestamp(6)")
            .put("binary", "varbinary")
            .put("object", "json")
            .put("nested", "json")
            .put("flattened", "json")
            .put("geo_point", "json")
            .put("geo_shape", "json")
            .buildOrThrow();

    private final ElasticsearchConfig config;
    private final TypeManager typeManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final java.net.http.HttpClient httpClient;
    private final Supplier<Map<String, ElasticsearchTable>> tables;
    private final Set<String> unmappedFieldTypes = ConcurrentHashMap.newKeySet();

    @Inject
    public ElasticsearchClient(ElasticsearchConfig config, TypeManager typeManager)
    {
        this.config = requireNonNull(config, "config is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.httpClient = createHttpClient(config);

        Supplier<Map<String, ElasticsearchTable>> supplier = () -> {
            try {
                return loadTables();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        if (config.getMetadataCacheTtlSeconds() <= 0) {
            tables = Suppliers.memoize(supplier);
        }
        else {
            tables = Suppliers.memoizeWithExpiration(
                    supplier,
                    config.getMetadataCacheTtlSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    public Set<String> getTableNames()
    {
        return tables.get().keySet();
    }

    public ElasticsearchTable getTable(String indexName)
    {
        requireNonNull(indexName, "indexName is null");
        return tables.get().get(indexName);
    }

    public ElasticsearchSearchSession openSearchSession(String indexName, List<ElasticsearchColumnHandle> columnHandles)
    {
        return new ElasticsearchSearchSession(this, config, objectMapper, indexName, columnHandles);
    }

    /**
     * Encodes an index name so that it can be used as a single URI path segment.
     */
    static String encodePathSegment(String value)
    {
        // URLEncoder is meant for query strings, where a space is encoded as '+'
        return URLEncoder.encode(value, UTF_8).replace("+", "%20");
    }

    JsonNode getJson(String path)
            throws IOException
    {
        HttpRequest request = newRequest(path).GET().build();
        HttpResponse<String> response = send(request);
        ensureSuccess(response);
        return parseJson(response.body());
    }

    JsonNode postJson(String path, ObjectNode body)
            throws IOException
    {
        HttpResponse<String> response = send(postRequest(path, body));
        ensureSuccess(response);
        return parseJson(response.body());
    }

    /**
     * Posts a request and returns an empty result instead of failing when Elasticsearch answers with an error status.
     */
    Optional<JsonNode> tryPostJson(String path, ObjectNode body)
            throws IOException
    {
        HttpResponse<String> response = send(postRequest(path, body));
        if (!isSuccess(response.statusCode())) {
            return Optional.empty();
        }
        return Optional.of(parseJson(response.body()));
    }

    void deleteBestEffort(String path, ObjectNode body)
    {
        try {
            HttpRequest request = newRequest(path)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), UTF_8))
                    .build();
            HttpResponse<String> response = send(request);
            if (!isSuccess(response.statusCode())) {
                log.warn("Failed to release Elasticsearch search context with status %s: %s", response.statusCode(), response.body());
            }
        }
        catch (IOException | RuntimeException e) {
            log.warn(e, "Failed to release Elasticsearch search context");
        }
    }

    private Map<String, ElasticsearchTable> loadTables()
            throws IOException
    {
        JsonNode root = getJson("/_mapping");
        Map<String, ElasticsearchTable> result = new LinkedHashMap<>();

        for (Map.Entry<String, JsonNode> index : root.properties()) {
            String indexName = index.getKey();
            if (indexName.startsWith(".")) {
                // System index, for example .kibana, .security or a .ds- backing index of a data stream.
                continue;
            }

            JsonNode properties = index.getValue().path("mappings").path("properties");
            if (!properties.isObject() || properties.isEmpty()) {
                log.warn("Skipping Elasticsearch index %s because it has no mapped fields", indexName);
                continue;
            }

            List<ElasticsearchColumn> columns = new ArrayList<>();
            Set<String> columnNames = new HashSet<>();
            expandFields(properties, "", true, columns, columnNames);
            if (columns.isEmpty()) {
                log.warn("Skipping Elasticsearch index %s because none of its fields can be mapped to a Trino type", indexName);
                continue;
            }
            result.put(indexName, new ElasticsearchTable(indexName, columns));
        }
        return ImmutableMap.copyOf(result);
    }

    /**
     * Registers every mapped field as a column and every entry of a {@code fields} block as an additional, independent
     * column named {@code parent.subField}. Sub-fields are never part of {@code _source}, so they are read from the
     * {@code fields} section of a search hit instead.
     */
    private void expandFields(
            JsonNode properties,
            String prefix,
            boolean fromSource,
            List<ElasticsearchColumn> columns,
            Set<String> columnNames)
    {
        for (Map.Entry<String, JsonNode> field : properties.properties()) {
            String fieldName = field.getKey();
            JsonNode definition = field.getValue();
            String columnName = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
            String esFieldType = definition.path("type").asText("");
            if (esFieldType.isEmpty() && definition.path("properties").isObject()) {
                // Elasticsearch omits the type of an object field unless it was declared explicitly
                esFieldType = "object";
            }

            if (esFieldType.isEmpty()) {
                log.warn("Ignoring Elasticsearch field %s because its mapping has no type", columnName);
            }
            else {
                Type type = mapType(esFieldType);
                if (type == null) {
                    if (unmappedFieldTypes.add(esFieldType)) {
                        log.warn("Ignoring Elasticsearch fields of type %s because there is no Trino type mapping for it", esFieldType);
                    }
                }
                else if (!columnNames.add(columnName)) {
                    log.warn("Ignoring Elasticsearch field %s because a field with the same column name already exists", columnName);
                }
                else {
                    columns.add(new ElasticsearchColumn(columnName, type, esFieldType, fromSource));
                }
            }

            JsonNode subFields = definition.path("fields");
            if (subFields.isObject() && !subFields.isEmpty()) {
                expandFields(subFields, columnName, false, columns, columnNames);
            }
        }
    }

    private Type mapType(String esFieldType)
    {
        String sqlType = SQL_TYPE_BY_FIELD_TYPE.get(esFieldType);
        if (sqlType == null) {
            return null;
        }
        return typeManager.fromSqlType(sqlType);
    }

    private HttpRequest postRequest(String path, ObjectNode body)
            throws IOException
    {
        return newRequest(path)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), UTF_8))
                .build();
    }

    private HttpRequest.Builder newRequest(String path)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");

        String authorization = authorizationHeader();
        if (!authorization.isEmpty()) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private String authorizationHeader()
    {
        if (config.getUsername().isEmpty()) {
            return "";
        }
        String credentials = config.getUsername() + ":" + config.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
    }

    private URI resolve(String path)
    {
        String base = config.getElasticsearchUri().toString();
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
            throw new IOException("Interrupted while waiting for Elasticsearch response", e);
        }
    }

    private JsonNode parseJson(String body)
            throws IOException
    {
        JsonNode root = objectMapper.readTree(body);
        return root == null ? objectMapper.createObjectNode() : root;
    }

    private static boolean isSuccess(int status)
    {
        return status >= 200 && status < 300;
    }

    private static void ensureSuccess(HttpResponse<String> response)
            throws IOException
    {
        if (!isSuccess(response.statusCode())) {
            throw new IOException(format(
                    "Elasticsearch request to %s failed with status %s: %s",
                    response.uri(),
                    response.statusCode(),
                    response.body()));
        }
    }

    private static java.net.http.HttpClient createHttpClient(ElasticsearchConfig config)
    {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);

        if (!config.isTlsVerify()) {
            // Trust any certificate and skip host name verification, which is required for the self signed
            // certificates that Elasticsearch generates on first start.
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            builder.sslContext(trustAllSslContext()).sslParameters(sslParameters);
        }
        return builder.build();
    }

    private static SSLContext trustAllSslContext()
    {
        TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager()
                {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers()
                    {
                        return new X509Certificate[0];
                    }
                }};

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());
            return sslContext;
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to create a trust-all SSL context", e);
        }
    }
}
