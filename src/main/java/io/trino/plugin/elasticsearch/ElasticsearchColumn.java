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
import io.trino.spi.type.Type;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public final class ElasticsearchColumn
{
    private final String name;
    private final Type type;
    private final String esFieldType;
    private final ElasticsearchColumnSource columnSource;
    private final boolean hidden;

    @JsonCreator
    public ElasticsearchColumn(
            @JsonProperty("name") String name,
            @JsonProperty("type") Type type,
            @JsonProperty("esFieldType") String esFieldType,
            @JsonProperty("columnSource") ElasticsearchColumnSource columnSource,
            @JsonProperty("hidden") boolean hidden)
    {
        this.name = requireNonNull(name, "name is null");
        this.type = requireNonNull(type, "type is null");
        this.esFieldType = requireNonNull(esFieldType, "esFieldType is null");
        this.columnSource = requireNonNull(columnSource, "columnSource is null");
        this.hidden = hidden;
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public Type getType()
    {
        return type;
    }

    @JsonProperty
    public String getEsFieldType()
    {
        return esFieldType;
    }

    @JsonProperty
    public ElasticsearchColumnSource getColumnSource()
    {
        return columnSource;
    }

    @JsonProperty
    public boolean isHidden()
    {
        return hidden;
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
        ElasticsearchColumn that = (ElasticsearchColumn) o;
        return hidden == that.hidden &&
                columnSource == that.columnSource &&
                name.equals(that.name) &&
                type.equals(that.type) &&
                esFieldType.equals(that.esFieldType);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, type, esFieldType, columnSource, hidden);
    }
}
