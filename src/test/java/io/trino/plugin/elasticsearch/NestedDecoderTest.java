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

import io.trino.plugin.elasticsearch.client.SearchDocument;
import io.trino.plugin.elasticsearch.decoders.ArrayDecoder;
import io.trino.plugin.elasticsearch.decoders.BigintDecoder;
import io.trino.plugin.elasticsearch.decoders.RowDecoder;
import io.trino.plugin.elasticsearch.decoders.VarcharDecoder;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.SqlRow;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.RowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NestedDecoderTest
{
    @Test
    void testNestedArrayOfRows()
    {
        ArrayType arrayType = nestedAddressType();
        ArrayDecoder decoder = nestedAddressDecoder();

        List<Map<String, Object>> addresses = List.of(
                Map.of("city", "Beijing", "zip", 100000),
                Map.of("city", "Shanghai", "zip", 200000));
        SearchDocument document = new SearchDocument(
                "1",
                1.0f,
                Map.of("addresses", addresses),
                "{}",
                2,
                Map.of());
        BlockBuilder builder = arrayType.createBlockBuilder(null, 1);

        decoder.decode(document, () -> addresses, builder);
        Block block = builder.build();

        Block rows = (Block) arrayType.getObject(block, 0);
        assertEquals(2, rows.getPositionCount());
        assertRow((RowType) arrayType.getElementType(), rows, 0, "Beijing", 100000L);
        assertRow((RowType) arrayType.getElementType(), rows, 1, "Shanghai", 200000L);
    }

    @Test
    void testSingleNestedObjectIsNormalizedToArray()
    {
        ArrayType arrayType = nestedAddressType();
        ArrayDecoder decoder = nestedAddressDecoder();
        Map<String, Object> address = Map.of("city", "Beijing", "zip", 100000);
        SearchDocument document = new SearchDocument(
                "1",
                1.0f,
                Map.of("addresses", address),
                "{}",
                2,
                Map.of());
        BlockBuilder builder = arrayType.createBlockBuilder(null, 1);

        decoder.decode(document, () -> address, builder);
        Block rows = (Block) arrayType.getObject(builder.build(), 0);

        assertEquals(1, rows.getPositionCount());
        assertRow((RowType) arrayType.getElementType(), rows, 0, "Beijing", 100000L);
    }

    @Test
    void testKeywordMultiFieldInsideRowFallsBackToParentValue()
    {
        RowType rowType = RowType.from(List.of(
                RowType.field("city", VARCHAR),
                RowType.field("city.keyword", VARCHAR)));
        RowDecoder decoder = new RowDecoder(
                "address",
                List.of("city", "city.keyword"),
                List.of(
                        new VarcharDecoder("address.city"),
                        new VarcharDecoder("address.city.keyword")));
        Map<String, Object> address = Map.of("city", "Beijing");
        SearchDocument document = new SearchDocument(
                "1",
                1.0f,
                Map.of("address", address),
                "{}",
                2,
                Map.of());
        BlockBuilder builder = rowType.createBlockBuilder(null, 1);

        decoder.decode(document, () -> address, builder);
        SqlRow row = (SqlRow) rowType.getObject(builder.build(), 0);

        assertEquals("Beijing", VARCHAR.getSlice(row.getUnderlyingFieldBlock(0), row.getUnderlyingFieldPosition(0)).toStringUtf8());
        assertEquals("Beijing", VARCHAR.getSlice(row.getUnderlyingFieldBlock(1), row.getUnderlyingFieldPosition(1)).toStringUtf8());
    }

    private static ArrayType nestedAddressType()
    {
        return new ArrayType(RowType.from(List.of(
                RowType.field("city", VARCHAR),
                RowType.field("zip", BIGINT))));
    }

    private static ArrayDecoder nestedAddressDecoder()
    {
        return new ArrayDecoder(new RowDecoder(
                "addresses",
                List.of("city", "zip"),
                List.of(
                        new VarcharDecoder("addresses.city"),
                        new BigintDecoder("addresses.zip"))));
    }

    private static void assertRow(RowType rowType, Block rows, int position, String city, long zip)
    {
        SqlRow row = (SqlRow) rowType.getObject(rows, position);
        assertEquals(city, VARCHAR.getSlice(row.getUnderlyingFieldBlock(0), row.getUnderlyingFieldPosition(0)).toStringUtf8());
        assertEquals(zip, BIGINT.getLong(row.getUnderlyingFieldBlock(1), row.getUnderlyingFieldPosition(1)));
    }
}
