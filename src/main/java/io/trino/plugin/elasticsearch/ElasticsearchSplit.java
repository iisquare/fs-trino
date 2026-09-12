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
import com.google.common.collect.ImmutableList;
import io.trino.spi.HostAddress;
import io.trino.spi.connector.ConnectorSplit;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static io.airlift.slice.SizeOf.instanceSize;
import static java.util.Objects.requireNonNull;

public final class ElasticsearchSplit
        implements ConnectorSplit
{
    private static final int INSTANCE_SIZE = instanceSize(ElasticsearchSplit.class);

    private final String schemaName;
    private final String indexName;
    private final URI uri;
    private final List<HostAddress> addresses;

    @JsonCreator
    public ElasticsearchSplit(
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("indexName") String indexName,
            @JsonProperty("uri") URI uri)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.indexName = requireNonNull(indexName, "indexName is null");
        this.uri = requireNonNull(uri, "uri is null");
        this.addresses = ImmutableList.of(HostAddress.fromUri(uri));
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

    @JsonProperty
    public URI getUri()
    {
        return uri;
    }

    @Override
    public boolean isRemotelyAccessible()
    {
        return true;
    }

    @Override
    public List<HostAddress> getAddresses()
    {
        return addresses;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + estimatedSizeOf(schemaName)
                + estimatedSizeOf(indexName)
                + estimatedSizeOf(uri.toString())
                + estimatedSizeOf(addresses, HostAddress::getRetainedSizeInBytes);
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
        ElasticsearchSplit that = (ElasticsearchSplit) o;
        return schemaName.equals(that.schemaName) &&
                indexName.equals(that.indexName) &&
                uri.equals(that.uri);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaName, indexName, uri);
    }
}
