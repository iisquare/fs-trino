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

import com.google.common.collect.ImmutableList;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.type.Type;

import java.util.List;

import static java.util.Objects.requireNonNull;

public final class HttpRecordSet
        implements RecordSet
{
    private final HttpClient client;
    private final HttpConfig config;
    private final HttpSplit split;
    private final List<HttpColumnHandle> columnHandles;
    private final List<Type> columnTypes;

    public HttpRecordSet(
            HttpClient client,
            HttpConfig config,
            HttpSplit split,
            List<HttpColumnHandle> columnHandles)
    {
        this.client = requireNonNull(client, "client is null");
        this.config = requireNonNull(config, "config is null");
        this.split = requireNonNull(split, "split is null");
        this.columnHandles = ImmutableList.copyOf(requireNonNull(columnHandles, "columnHandles is null"));

        ImmutableList.Builder<Type> types = ImmutableList.builder();
        for (HttpColumnHandle handle : columnHandles) {
            types.add(handle.getColumnType());
        }
        this.columnTypes = types.build();
    }

    @Override
    public List<Type> getColumnTypes()
    {
        return columnTypes;
    }

    @Override
    public RecordCursor cursor()
    {
        return new HttpRecordCursor(client, config, split, columnHandles);
    }
}
