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

import static java.util.Objects.requireNonNull;

// PATCH(fs-trino): a translated any_match predicate. The path is the Elasticsearch nested path and the query is the
// predicate to apply inside that path. Keeping this on the table handle makes the pushdown visible in EXPLAIN and
// allows it to be serialized to workers with the rest of the connector handle.
public record ElasticsearchNestedQuery(String path, String query)
{
    public ElasticsearchNestedQuery
    {
        requireNonNull(path, "path is null");
        requireNonNull(query, "query is null");
    }
}
