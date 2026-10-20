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
import io.trino.spi.predicate.TupleDomain;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static io.trino.plugin.elasticsearch.ElasticsearchMetadata.likeToPhrase;
import static io.trino.plugin.elasticsearch.ElasticsearchQueryBuilder.buildSearchQuery;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ElasticsearchPhrasePushdownTest
{
    @Test
    void testLikeToPhrase()
    {
        assertPhrase("%建设工程%", Optional.empty(), Optional.of("建设工程"));
        assertPhrase("%foo%", Optional.empty(), Optional.of("foo"));
        assertPhrase("%foo%bar%", Optional.empty(), Optional.empty());
        assertPhrase("%foo_bar%", Optional.empty(), Optional.empty());
        assertPhrase("%foo", Optional.empty(), Optional.empty());
        assertPhrase("foo%", Optional.empty(), Optional.empty());
        assertPhrase("%%", Optional.empty(), Optional.empty());
        assertPhrase("% %", Optional.empty(), Optional.empty());

        assertPhrase("\\%foo%", Optional.of("\\"), Optional.empty());
        assertPhrase("%foo\\%%", Optional.of("\\"), Optional.of("foo%"));
        assertPhrase("%foo\\\\bar%", Optional.of("\\"), Optional.of("foo\\bar"));
    }

    @Test
    void testPhraseQueryDsl()
    {
        assertEquals(
                "{\"bool\":{\"filter\":[{\"match_phrase\":{\"name\":\"建设工程\"}}]}}",
                buildSearchQuery(
                        TupleDomain.all(),
                        Optional.empty(),
                        Map.of(),
                        Map.of("name", "建设工程"))
                        .toString());
    }

    private static void assertPhrase(String pattern, Optional<String> escape, Optional<String> expected)
    {
        assertEquals(
                expected,
                likeToPhrase(
                        Slices.utf8Slice(pattern),
                        escape.map(Slices::utf8Slice)));
    }
}
