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
import io.trino.spi.connector.ColumnMetadata;

import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

public final class ElasticsearchTable
{
    private final String name;
    private final List<ElasticsearchColumn> columns;
    private final List<ColumnMetadata> columnMetadata;

    @JsonCreator
    public ElasticsearchTable(
            @JsonProperty("name") String name,
            @JsonProperty("columns") List<ElasticsearchColumn> columns)
    {
        this.name = requireNonNull(name, "name is null");
        this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns is null"));

        ImmutableList.Builder<ColumnMetadata> builder = ImmutableList.builder();
        for (ElasticsearchColumn column : this.columns) {
            builder.add(new ColumnMetadata(column.getName(), column.getType()));
        }
        this.columnMetadata = builder.build();
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public List<ElasticsearchColumn> getColumns()
    {
        return columns;
    }

    public List<ColumnMetadata> getColumnMetadata()
    {
        return columnMetadata;
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
        ElasticsearchTable that = (ElasticsearchTable) o;
        return name.equals(that.name) && columns.equals(that.columns);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, columns);
    }
}
