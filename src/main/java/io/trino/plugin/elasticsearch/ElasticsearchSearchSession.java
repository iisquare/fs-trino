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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Reads the rows of a single Elasticsearch index page by page.
 * <p>
 * Paging uses a point in time (PIT) with {@code search_after}, which gives a stable view of the index and needs no
 * extra memory on the Elasticsearch side. Clusters that do not offer the PIT API fall back to the scroll API.
 */
final class ElasticsearchSearchSession
        implements AutoCloseable
{
    private static final Logger log = Logger.get(ElasticsearchSearchSession.class);

    private static final String SCROLL_KEEP_ALIVE = "1m";
    private static final String SEARCH_AFTER_SORT_FIELD = "_shard_doc";

    private final ElasticsearchClient client;
    private final ElasticsearchConfig config;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final List<String> fieldNames;

    private String pitId;
    private String scrollId;
    private boolean scrollMode;
    private JsonNode lastSort;
    private JsonNode pendingPage;
    private boolean started;
    private boolean done;

    ElasticsearchSearchSession(
            ElasticsearchClient client,
            ElasticsearchConfig config,
            ObjectMapper objectMapper,
            String indexName,
            List<ElasticsearchColumnHandle> columnHandles)
    {
        this.client = requireNonNull(client, "client is null");
        this.config = requireNonNull(config, "config is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.indexName = requireNonNull(indexName, "indexName is null");
        requireNonNull(columnHandles, "columnHandles is null");

        ImmutableList.Builder<String> fields = ImmutableList.builder();
        for (ElasticsearchColumnHandle columnHandle : columnHandles) {
            if (columnHandle.getColumnSource() == ElasticsearchColumnSource.MULTI_FIELD) {
                // Sub-fields of a multi-field are never part of _source and have to be requested explicitly.
                fields.add(columnHandle.getColumnName());
            }
        }
        this.fieldNames = fields.build();
    }

    /**
     * Returns the hits of the next page, or an empty list once the index has been read completely.
     */
    List<JsonNode> fetchNextPage()
            throws IOException
    {
        if (done) {
            return List.of();
        }
        if (!started) {
            started = true;
            start();
        }

        JsonNode response;
        if (pendingPage != null) {
            // The search that opened a scroll already contains the first page of hits
            response = pendingPage;
            pendingPage = null;
        }
        else {
            response = scrollMode ? fetchScrollPage() : fetchPitPage();
        }

        JsonNode hits = response.path("hits").path("hits");
        List<JsonNode> rows = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            rows.add(hit);
        }

        String nextPitId = response.path("pit_id").asText("");
        if (!nextPitId.isEmpty()) {
            // Elasticsearch may hand out a new point in time id with every search, and only the latest one
            // can be used to release the point in time.
            pitId = nextPitId;
        }

        if (rows.size() < config.getPageSize()) {
            done = true;
            releaseContext();
        }
        else {
            lastSort = rows.get(rows.size() - 1).path("sort");
        }
        return rows;
    }

    @Override
    public void close()
    {
        done = true;
        releaseContext();
    }

    private void start()
            throws IOException
    {
        Optional<JsonNode> response = client.tryPostJson(
                "/" + ElasticsearchClient.encodePathSegment(indexName) + "/_pit?keep_alive=" + config.getPitKeepAliveSeconds() + "s",
                objectMapper.createObjectNode());

        String id = response.map(node -> node.path("id").asText("")).orElse("");
        if (!id.isEmpty()) {
            pitId = id;
            return;
        }

        log.warn("Elasticsearch point-in-time is not available for index %s, falling back to the scroll API", indexName);
        startScroll();
    }

    private void startScroll()
            throws IOException
    {
        JsonNode response = client.postJson(
                "/" + ElasticsearchClient.encodePathSegment(indexName) + "/_search?scroll=" + SCROLL_KEEP_ALIVE,
                searchBody());

        String id = response.path("_scroll_id").asText("");
        if (id.isEmpty()) {
            throw new IOException("Elasticsearch scroll response for index '" + indexName + "' did not contain a scroll id");
        }
        scrollId = id;
        scrollMode = true;
        pendingPage = response;
    }

    private JsonNode fetchPitPage()
            throws IOException
    {
        ObjectNode body = searchBody();
        ObjectNode pit = body.putObject("pit");
        pit.put("id", pitId);
        pit.put("keep_alive", config.getPitKeepAliveSeconds() + "s");
        body.putArray("sort").addObject().put(SEARCH_AFTER_SORT_FIELD, "asc");
        if (lastSort != null && lastSort.isArray()) {
            body.set("search_after", lastSort);
        }
        return client.postJson("/_search", body);
    }

    private JsonNode fetchScrollPage()
            throws IOException
    {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("scroll", SCROLL_KEEP_ALIVE);
        body.put("scroll_id", scrollId);

        JsonNode response = client.postJson("/_search/scroll", body);
        String id = response.path("_scroll_id").asText("");
        if (!id.isEmpty()) {
            scrollId = id;
        }
        return response;
    }

    private ObjectNode searchBody()
    {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", config.getPageSize());
        body.put("track_total_hits", false);
        body.putObject("query").putObject("match_all");
        body.put("_source", true);
        if (!fieldNames.isEmpty()) {
            ArrayNode fields = body.putArray("fields");
            fieldNames.forEach(fields::add);
        }
        return body;
    }

    private void releaseContext()
    {
        if (pitId != null) {
            String id = pitId;
            pitId = null;
            client.deleteBestEffort("/_pit", bodyWith("id", id));
        }
        if (scrollId != null) {
            String id = scrollId;
            scrollId = null;
            client.deleteBestEffort("/_search/scroll", bodyWith("scroll_id", id));
        }
    }

    private ObjectNode bodyWith(String name, String value)
    {
        ObjectNode body = objectMapper.createObjectNode();
        body.put(name, value);
        return body;
    }
}
