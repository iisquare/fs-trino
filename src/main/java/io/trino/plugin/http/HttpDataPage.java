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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

final class HttpDataPage
{
    private final List<JsonNode> rows;
    private final OptionalLong nextOffset;

    HttpDataPage(List<JsonNode> rows, OptionalLong nextOffset)
    {
        this.rows = ImmutableList.copyOf(requireNonNull(rows, "rows is null"));
        this.nextOffset = requireNonNull(nextOffset, "nextOffset is null");
    }

    List<JsonNode> getRows()
    {
        return rows;
    }

    OptionalLong getNextOffset()
    {
        return nextOffset;
    }
}
