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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public final class ElasticsearchTableHandle
        implements ConnectorTableHandle
{
    private final String schemaName;
    private final String indexName;

    @JsonCreator
    public ElasticsearchTableHandle(
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("indexName") String indexName)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.indexName = requireNonNull(indexName, "indexName is null");
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public String getIndexName()
    {
        return indexName;
    }

    public SchemaTableName toSchemaTableName()
    {
        return new SchemaTableName(schemaName, indexName);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ElasticsearchTableHandle that = (ElasticsearchTableHandle) o;
        return schemaName.equals(that.schemaName) && indexName.equals(that.indexName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaName, indexName);
    }

    @Override
    public String toString()
    {
        return schemaName + ":" + indexName;
    }
}
