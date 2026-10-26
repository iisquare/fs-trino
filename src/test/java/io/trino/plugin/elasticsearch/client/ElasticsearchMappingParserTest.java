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
package io.trino.plugin.elasticsearch.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchMappingParserTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testNestedMappingKeepsItsOwnType()
            throws Exception
    {
        JsonNode properties = OBJECT_MAPPER.readTree("""
                {
                  "addresses": {
                    "type": "nested",
                    "properties": {
                      "city": {"type": "keyword"},
                      "zip": {"type": "integer"}
                    }
                  },
                  "attributes": {
                    "type": "object",
                    "properties": {
                      "color": {"type": "keyword"}
                    }
                  }
                }
                """);

        IndexMetadata.ObjectType schema = ElasticsearchClient.parseType(properties, NullNode.getInstance());
        assertEquals(2, schema.fields().size());

        IndexMetadata.Field addresses = schema.fields().get(0);
        assertEquals("addresses", addresses.name());
        IndexMetadata.NestedType nestedType = assertInstanceOf(IndexMetadata.NestedType.class, addresses.type());
        assertEquals(2, nestedType.fields().size());
        assertEquals("city", nestedType.fields().get(0).name());
        assertTrue(nestedType.fields().get(0).supportsPredicates());
        assertInstanceOf(IndexMetadata.PrimitiveType.class, nestedType.fields().get(0).type());
        assertEquals(
                nestedType,
                OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsString(nestedType), IndexMetadata.Type.class));

        IndexMetadata.Field attributes = schema.fields().get(1);
        assertEquals("attributes", attributes.name());
        assertInstanceOf(IndexMetadata.ObjectType.class, attributes.type());
    }

    @Test
    void testUnsafeKeywordMappingsDisablePredicatePushdown()
            throws Exception
    {
        JsonNode properties = OBJECT_MAPPER.readTree("""
                {
                  "not_indexed": {"type": "keyword", "index": false},
                  "too_long": {"type": "keyword", "ignore_above": 256},
                  "normalized": {"type": "keyword", "normalizer": "lowercase"}
                }
                """);

        IndexMetadata.ObjectType schema = ElasticsearchClient.parseType(properties, NullNode.getInstance());

        assertEquals(3, schema.fields().size());
        schema.fields().forEach(field -> assertFalse(field.supportsPredicates(), field.name()));
    }
}
