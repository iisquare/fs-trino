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

public final class HttpSplit
        implements ConnectorSplit
{
    private static final int INSTANCE_SIZE = instanceSize(HttpSplit.class);

    private final String schemaName;
    private final String tableName;
    private final URI baseUri;
    private final List<HostAddress> addresses;

    @JsonCreator
    public HttpSplit(
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("baseUri") URI baseUri)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.tableName = requireNonNull(tableName, "tableName is null");
        this.baseUri = requireNonNull(baseUri, "baseUri is null");
        this.addresses = ImmutableList.of(HostAddress.fromUri(baseUri));
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public String getTableName()
    {
        return tableName;
    }

    @JsonProperty
    public URI getBaseUri()
    {
        return baseUri;
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
                + estimatedSizeOf(tableName)
                + estimatedSizeOf(baseUri.toString())
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
        HttpSplit that = (HttpSplit) o;
        return schemaName.equals(that.schemaName) &&
                tableName.equals(that.tableName) &&
                baseUri.equals(that.baseUri);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaName, tableName, baseUri);
    }
}
