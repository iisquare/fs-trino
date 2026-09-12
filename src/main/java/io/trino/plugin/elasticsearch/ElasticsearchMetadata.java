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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableVersion;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.TableNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

public final class ElasticsearchMetadata
        implements ConnectorMetadata
{
    private final ElasticsearchClient client;
    private final ElasticsearchConfig config;

    @Inject
    public ElasticsearchMetadata(ElasticsearchClient client, ElasticsearchConfig config)
    {
        this.client = requireNonNull(client, "client is null");
        this.config = requireNonNull(config, "config is null");
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return ImmutableList.of(config.getDefaultSchemaName());
    }

    @Override
    public ElasticsearchTableHandle getTableHandle(
            ConnectorSession session,
            SchemaTableName tableName,
            Optional<ConnectorTableVersion> startVersion,
            Optional<ConnectorTableVersion> endVersion)
    {
        if (startVersion.isPresent() || endVersion.isPresent()) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support versioned tables");
        }

        if (!isDefaultSchema(tableName.getSchemaName())) {
            return null;
        }
        if (client.getTable(tableName.getTableName()) == null) {
            return null;
        }
        return new ElasticsearchTableHandle(tableName.getSchemaName(), tableName.getTableName());
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ElasticsearchTableHandle handle = (ElasticsearchTableHandle) tableHandle;
        ElasticsearchTable table = client.getTable(handle.getIndexName());
        if (table == null) {
            throw new TableNotFoundException(handle.toSchemaTableName());
        }
        return new ConnectorTableMetadata(handle.toSchemaTableName(), table.getColumnMetadata());
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> optionalSchemaName)
    {
        if (optionalSchemaName.isPresent() && !isDefaultSchema(optionalSchemaName.get())) {
            return ImmutableList.of();
        }

        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (String indexName : client.getTableNames()) {
            builder.add(new SchemaTableName(config.getDefaultSchemaName(), indexName));
        }
        return builder.build();
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ElasticsearchTableHandle handle = (ElasticsearchTableHandle) tableHandle;
        ElasticsearchTable table = client.getTable(handle.getIndexName());
        if (table == null) {
            throw new TableNotFoundException(handle.toSchemaTableName());
        }

        ImmutableMap.Builder<String, ColumnHandle> columns = ImmutableMap.builder();
        int ordinalPosition = 0;
        for (ElasticsearchColumn column : table.getColumns()) {
            columns.put(
                    column.getName(),
                    new ElasticsearchColumnHandle(
                            column.getName(),
                            column.getType(),
                            column.getEsFieldType(),
                            column.isFromSource(),
                            ordinalPosition));
            ordinalPosition++;
        }
        return columns.buildOrThrow();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        for (SchemaTableName tableName : listTables(session, prefix)) {
            ElasticsearchTable table = client.getTable(tableName.getTableName());
            if (table != null) {
                columns.put(tableName, table.getColumnMetadata());
            }
        }
        return columns.buildOrThrow();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((ElasticsearchColumnHandle) columnHandle).getColumnMetadata();
    }

    private boolean isDefaultSchema(String schemaName)
    {
        return config.getDefaultSchemaName().equals(schemaName);
    }

    private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getTable().isEmpty()) {
            return listTables(session, prefix.getSchema());
        }
        return ImmutableList.of(prefix.toSchemaTableName());
    }
}
