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

import io.airlift.slice.Slices;
import io.trino.plugin.elasticsearch.client.ElasticsearchClient;
import io.trino.plugin.elasticsearch.client.IndexMetadata;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ProjectionApplicationResult;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.FieldDereference;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeDescriptor;
import io.trino.spi.type.TypeId;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeOperators;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.trino.plugin.elasticsearch.ElasticsearchTableHandle.Type.SCAN;
import static io.trino.spi.expression.StandardFunctions.LIKE_FUNCTION_NAME;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ElasticsearchNestedTypeMappingTest
{
    @Test
    void testNestedArrayRequiresMetaIsArray()
            throws IOException
    {
        assertEquals(RowType.class, nestedColumn(false).type().getClass());
        assertEquals(ArrayType.class, nestedColumn(true).type().getClass());
    }

    @Test
    void testProjectedNestedFieldCarriesNestedPath()
            throws IOException
    {
        ElasticsearchConfig config = new ElasticsearchConfig().setHosts(List.of("localhost"));
        IndexMetadata indexMetadata = new IndexMetadata(new IndexMetadata.ObjectType(List.of(
                new IndexMetadata.Field(
                        false,
                        false,
                        "address",
                        new IndexMetadata.NestedType(List.of(
                                new IndexMetadata.Field(false, false, "city", new IndexMetadata.PrimitiveType("keyword"))))))));
        StubElasticsearchClient client = new StubElasticsearchClient(config, indexMetadata);
        try {
            ElasticsearchMetadata metadata = new ElasticsearchMetadata(new EmptyTypeManager(), client, config);
            ElasticsearchTableHandle table = new ElasticsearchTableHandle(SCAN, "default", "orders", Optional.empty());
            ElasticsearchColumnHandle address = (ElasticsearchColumnHandle) metadata.getColumnHandles(null, table).get("address");
            Variable addressVariable = new Variable("address", address.type());

            ProjectionApplicationResult<ConnectorTableHandle> result = metadata.applyProjection(
                    null,
                    table.withColumns(Set.of(address)),
                    List.of(new FieldDereference(VARCHAR, addressVariable, 0)),
                    Map.of(addressVariable.getName(), address))
                    .orElseThrow();
            ElasticsearchColumnHandle city = (ElasticsearchColumnHandle) result.getAssignments().getFirst().getColumn();

            assertEquals(List.of("address", "city"), city.path());
            assertEquals(List.of("address"), city.nestedPaths());

            Variable cityVariable = new Variable("city", VARCHAR);
            Call like = new Call(
                    BOOLEAN,
                    LIKE_FUNCTION_NAME,
                    List.of(
                            cityVariable,
                            new Constant(Slices.utf8Slice("%Bei%"), VARCHAR)));
            ConstraintApplicationResult<ConnectorTableHandle> filter = metadata.applyFilter(
                    null,
                    table.withColumns(Set.of(city)),
                    new Constraint(TupleDomain.all(), like, Map.of(cityVariable.getName(), city)))
                    .orElseThrow();
            ElasticsearchTableHandle filteredTable = (ElasticsearchTableHandle) filter.getHandle();

            assertEquals(
                    List.of(new ElasticsearchNestedQuery(
                            List.of("address"),
                            "{\"regexp\":{\"address.city\":\".*Bei.*\"}}")),
                    filteredTable.nestedQueries());
        }
        finally {
            client.close();
        }
    }

    private static ElasticsearchColumnHandle nestedColumn(boolean isArray)
            throws IOException
    {
        ElasticsearchConfig config = new ElasticsearchConfig().setHosts(List.of("localhost"));
        IndexMetadata metadata = new IndexMetadata(new IndexMetadata.ObjectType(List.of(
                new IndexMetadata.Field(
                        false,
                        isArray,
                        "address",
                        new IndexMetadata.NestedType(List.of(
                                new IndexMetadata.Field(false, false, "city", new IndexMetadata.PrimitiveType("keyword"))))))));
        StubElasticsearchClient client = new StubElasticsearchClient(config, metadata);
        try {
            ElasticsearchMetadata connectorMetadata = new ElasticsearchMetadata(new EmptyTypeManager(), client, config);
            Map<String, ColumnHandle> columns = connectorMetadata.getColumnHandles(
                    null,
                    new ElasticsearchTableHandle(SCAN, "default", "orders", Optional.empty()));
            return (ElasticsearchColumnHandle) columns.get("address");
        }
        finally {
            client.close();
        }
    }

    private static class StubElasticsearchClient
            extends ElasticsearchClient
    {
        private final IndexMetadata metadata;

        public StubElasticsearchClient(ElasticsearchConfig config, IndexMetadata metadata)
        {
            super(config, Optional.empty(), Optional.empty());
            this.metadata = metadata;
        }

        @Override
        public IndexMetadata getIndexMetadata(String index)
        {
            return metadata;
        }
    }

    private static class EmptyTypeManager
            implements TypeManager
    {
        @Override
        public Type getType(TypeDescriptor typeDescriptor)
        {
            return null;
        }

        @Override
        public Type fromSqlType(String type)
        {
            return null;
        }

        @Override
        public Type getType(TypeId id)
        {
            return null;
        }

        @Override
        public boolean isTypeRegistered(String typeName)
        {
            return false;
        }

        @Override
        public TypeOperators getTypeOperators()
        {
            return new TypeOperators();
        }
    }
}
