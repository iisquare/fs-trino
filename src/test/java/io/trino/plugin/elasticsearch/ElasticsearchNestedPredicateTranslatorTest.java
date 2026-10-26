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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.slice.Slices;
import io.trino.plugin.elasticsearch.client.IndexMetadata;
import io.trino.plugin.elasticsearch.decoders.VarcharDecoder;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.expression.StandardFunctions.AND_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.OR_FUNCTION_NAME;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ElasticsearchNestedPredicateTranslatorTest
{
    @Test
    void testAnyMatchWithConjunction()
    {
        RowType rowType = addressRowType();
        ElasticsearchColumnHandle column = nestedAddressColumn(rowType, "keyword");
        Variable nested = new Variable("nested_addresses", new ArrayType(rowType));
        Variable address = new Variable("address", rowType);
        Call cityEquals = new Call(
                BOOLEAN,
                EQUAL_OPERATOR_FUNCTION_NAME,
                List.of(
                        new FieldDereference(VARCHAR, address, 0),
                        new Constant(Slices.utf8Slice("Beijing"), VARCHAR)));
        Call zipGreaterThan = new Call(
                BOOLEAN,
                GREATER_THAN_OPERATOR_FUNCTION_NAME,
                List.of(
                        new FieldDereference(BIGINT, address, 1),
                        new Constant(100000L, BIGINT)));
        Call predicate = new Call(BOOLEAN, AND_FUNCTION_NAME, List.of(cityEquals, zipGreaterThan));

        Optional<ElasticsearchNestedQuery> translated = translate(nested, address, rowType, predicate, column);

        assertEquals(
                Optional.of(new ElasticsearchNestedQuery(
                        "addresses",
                        "{\"bool\":{\"filter\":[{\"term\":{\"addresses.city\":\"Beijing\"}},{\"range\":{\"addresses.zip\":{\"gt\":100000}}}]}}")),
                translated);
        assertEquals(
                "{\"bool\":{\"filter\":[{\"nested\":{\"path\":\"addresses\",\"query\":{\"bool\":{\"filter\":[{\"term\":{\"addresses.city\":\"Beijing\"}},{\"range\":{\"addresses.zip\":{\"gt\":100000}}}]}}}}]}}",
                ElasticsearchQueryBuilder.buildSearchQuery(
                        TupleDomain.all(),
                        Optional.empty(),
                        Map.of(),
                        Map.of(),
                        translated.stream().toList())
                        .toString());
    }

    @Test
    void testTextFieldIsNotPushedDownWithTermQuery()
    {
        RowType rowType = addressRowType();
        ElasticsearchColumnHandle column = nestedAddressColumn(rowType, "text");
        Variable nested = new Variable("nested_addresses", new ArrayType(rowType));
        Variable address = new Variable("address", rowType);
        Call cityEquals = new Call(
                BOOLEAN,
                EQUAL_OPERATOR_FUNCTION_NAME,
                List.of(
                        new FieldDereference(VARCHAR, address, 0),
                        new Constant(Slices.utf8Slice("Beijing"), VARCHAR)));

        assertEquals(
                Optional.empty(),
                translate(nested, address, rowType, cityEquals, column));
    }

    @Test
    void testAnyMatchWithDisjunction()
    {
        RowType rowType = addressRowType();
        ElasticsearchColumnHandle column = nestedAddressColumn(rowType, "keyword");
        Variable nested = new Variable("nested_addresses", new ArrayType(rowType));
        Variable address = new Variable("address", rowType);
        Call cityEquals = new Call(
                BOOLEAN,
                EQUAL_OPERATOR_FUNCTION_NAME,
                List.of(
                        new FieldDereference(VARCHAR, address, 0),
                        new Constant(Slices.utf8Slice("Beijing"), VARCHAR)));
        Call zipGreaterThan = new Call(
                BOOLEAN,
                GREATER_THAN_OPERATOR_FUNCTION_NAME,
                List.of(
                        new FieldDereference(BIGINT, address, 1),
                        new Constant(100000L, BIGINT)));
        Call predicate = new Call(BOOLEAN, OR_FUNCTION_NAME, List.of(cityEquals, zipGreaterThan));

        assertEquals(
                Optional.of(new ElasticsearchNestedQuery(
                        "addresses",
                        "{\"bool\":{\"should\":[{\"term\":{\"addresses.city\":\"Beijing\"}},{\"range\":{\"addresses.zip\":{\"gt\":100000}}}],\"minimum_should_match\":1}}")),
                translate(nested, address, rowType, predicate, column));
    }

    @Test
    void testNestedQueryJsonRoundTrip()
            throws Exception
    {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ElasticsearchNestedQuery> nestedQueries = new ArrayList<>();
        nestedQueries.add(new ElasticsearchNestedQuery("addresses", "{\"term\":{\"addresses.city\":\"Beijing\"}}"));

        String json = objectMapper.writeValueAsString(nestedQueries);
        List<ElasticsearchNestedQuery> deserialized = objectMapper.readValue(
                json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ElasticsearchNestedQuery.class));

        assertEquals(nestedQueries, deserialized);
    }

    private static Optional<ElasticsearchNestedQuery> translate(
            Variable nested,
            Variable address,
            RowType rowType,
            Call predicate,
            ElasticsearchColumnHandle column)
    {
        Lambda lambda = new Lambda(
                new FunctionType(List.of(rowType), BOOLEAN),
                List.of(address),
                predicate);
        Call anyMatch = new Call(
                BOOLEAN,
                new FunctionName("any_match"),
                List.of(nested, lambda));
        return ElasticsearchNestedPredicateTranslator.translateAnyMatch(anyMatch, Map.of(nested.getName(), column));
    }

    private static RowType addressRowType()
    {
        return RowType.from(List.of(
                RowType.field("city", VARCHAR),
                RowType.field("zip", BIGINT)));
    }

    private static ElasticsearchColumnHandle nestedAddressColumn(RowType rowType, String cityType)
    {
        IndexMetadata.NestedType nestedType = new IndexMetadata.NestedType(List.of(
                new IndexMetadata.Field(false, false, "city", new IndexMetadata.PrimitiveType(cityType)),
                new IndexMetadata.Field(false, false, "zip", new IndexMetadata.PrimitiveType("long"))));
        return new ElasticsearchColumnHandle(
                List.of("addresses"),
                new ArrayType(rowType),
                nestedType,
                new VarcharDecoder.Descriptor("addresses"),
                false);
    }
}
