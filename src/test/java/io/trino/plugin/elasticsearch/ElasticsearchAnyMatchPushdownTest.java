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
import io.trino.plugin.elasticsearch.decoders.VarcharDecoder;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.FieldDereference;
import io.trino.spi.expression.FunctionName;
import io.trino.spi.expression.Lambda;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.FunctionType;
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

import static io.trino.plugin.elasticsearch.ElasticsearchTableHandle.Type.SCAN;
import static io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ElasticsearchAnyMatchPushdownTest
{
    @Test
    void testApplyFilterStoresNestedQueryAndRemovesExpression()
            throws IOException
    {
        ElasticsearchConfig config = new ElasticsearchConfig().setHosts(List.of("localhost"));
        ElasticsearchClient client = new ElasticsearchClient(config, Optional.empty(), Optional.empty());
        try {
            ElasticsearchMetadata metadata = new ElasticsearchMetadata(new EmptyTypeManager(), client, config);

            RowType rowType = RowType.from(List.of(RowType.field("city", VARCHAR)));
            IndexMetadata.NestedType nestedType = new IndexMetadata.NestedType(List.of(
                    new IndexMetadata.Field(false, false, "city", new IndexMetadata.PrimitiveType("keyword"))));
            ElasticsearchColumnHandle column = new ElasticsearchColumnHandle(
                    List.of("addresses"),
                    new ArrayType(rowType),
                    nestedType,
                    new VarcharDecoder.Descriptor("addresses"),
                    false);
            Variable nested = new Variable("nested_addresses", new ArrayType(rowType));
            Variable address = new Variable("address", rowType);
            Call cityEquals = new Call(
                    BOOLEAN,
                    EQUAL_OPERATOR_FUNCTION_NAME,
                    List.of(
                            new FieldDereference(VARCHAR, address, 0),
                            new Constant(Slices.utf8Slice("Beijing"), VARCHAR)));
            Call anyMatch = new Call(
                    BOOLEAN,
                    new FunctionName("any_match"),
                    List.of(
                            nested,
                            new Lambda(
                                    new FunctionType(List.of(rowType), BOOLEAN),
                                    List.of(address),
                                    cityEquals)));
            ElasticsearchTableHandle table = new ElasticsearchTableHandle(SCAN, "default", "orders", Optional.empty());
            Constraint constraint = new Constraint(
                    TupleDomain.all(),
                    anyMatch,
                    Map.of(nested.getName(), column));

            ConstraintApplicationResult<ConnectorTableHandle> result = metadata.applyFilter(null, table, constraint).orElseThrow();
            ElasticsearchTableHandle pushedDown = (ElasticsearchTableHandle) result.getHandle();

            assertEquals(
                    List.of(new ElasticsearchNestedQuery("addresses", "{\"term\":{\"addresses.city\":\"Beijing\"}}")),
                    pushedDown.nestedQueries());
            assertEquals(Constant.TRUE, result.getRemainingExpression().orElseThrow());
        }
        finally {
            client.close();
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
