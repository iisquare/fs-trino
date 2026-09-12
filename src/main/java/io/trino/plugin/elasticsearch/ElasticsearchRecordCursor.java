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

import com.fasterxml.jackson.databind.JsonNode;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Timestamps;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

import static java.util.Objects.requireNonNull;

public final class ElasticsearchRecordCursor
        implements RecordCursor
{
    private static final long MICROS_PER_MILLI = 1_000;
    private static final long MICROS_PER_SECOND = 1_000_000;
    private static final int NANOS_PER_MICRO = 1_000;

    private static final String JSON_TYPE_NAME = "json";
    private static final String DATE_NANOS_FIELD_TYPE = "date_nanos";

    private final ElasticsearchClient client;
    private final ElasticsearchConfig config;
    private final ElasticsearchSplit split;
    private final List<ElasticsearchColumnHandle> columnHandles;

    private ElasticsearchSearchSession session;
    private Iterator<JsonNode> rows = List.<JsonNode>of().iterator();
    private Object[] currentRow;
    private boolean finished;
    private boolean closed;

    public ElasticsearchRecordCursor(
            ElasticsearchClient client,
            ElasticsearchConfig config,
            ElasticsearchSplit split,
            List<ElasticsearchColumnHandle> columnHandles)
    {
        this.client = requireNonNull(client, "client is null");
        this.config = requireNonNull(config, "config is null");
        this.split = requireNonNull(split, "split is null");
        this.columnHandles = requireNonNull(columnHandles, "columnHandles is null");
    }

    @Override
    public long getCompletedBytes()
    {
        return 0;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public Type getType(int field)
    {
        return columnHandles.get(field).getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        if (closed) {
            return false;
        }

        while (true) {
            if (rows.hasNext()) {
                currentRow = convertRow(rows.next());
                return true;
            }

            if (finished) {
                currentRow = null;
                return false;
            }

            if (session == null) {
                session = client.openSearchSession(split.getIndexName(), columnHandles);
            }

            List<JsonNode> page;
            try {
                page = session.fetchNextPage();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            if (page.isEmpty()) {
                finished = true;
                currentRow = null;
                return false;
            }
            rows = page.iterator();
        }
    }

    @Override
    public boolean getBoolean(int field)
    {
        return (Boolean) currentRow[field];
    }

    @Override
    public long getLong(int field)
    {
        return (Long) currentRow[field];
    }

    @Override
    public double getDouble(int field)
    {
        return (Double) currentRow[field];
    }

    @Override
    public Slice getSlice(int field)
    {
        return (Slice) currentRow[field];
    }

    @Override
    public Object getObject(int field)
    {
        return currentRow[field];
    }

    @Override
    public boolean isNull(int field)
    {
        return currentRow[field] == null;
    }

    @Override
    public void close()
    {
        closed = true;
        rows = List.<JsonNode>of().iterator();
        currentRow = null;
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private Object[] convertRow(JsonNode hit)
    {
        Object[] values = new Object[columnHandles.size()];
        for (int i = 0; i < columnHandles.size(); i++) {
            values[i] = convertColumn(hit, columnHandles.get(i));
        }
        return values;
    }

    private static Object convertColumn(JsonNode hit, ElasticsearchColumnHandle handle)
    {
        return switch (handle.getColumnSource()) {
            case MAPPED_FIELD -> convertNode(handle, fromSource(hit, handle.getColumnName()));
            case MULTI_FIELD -> convertNode(handle, fromFields(hit, handle.getColumnName()));
            case DOCUMENT_ID -> convertNode(handle, hit.path("_id"));
            case DOCUMENT_SOURCE -> {
                JsonNode source = hit.path("_source");
                yield source.isObject() ? Slices.utf8Slice(source.toString()) : null;
            }
        };
    }

    private static Object convertNode(ElasticsearchColumnHandle handle, JsonNode node)
    {
        return node == null || node.isNull() || node.isMissingNode()
                ? null
                : convertValue(handle.getColumnType(), handle.getEsFieldType(), node);
    }

    private static JsonNode fromSource(JsonNode hit, String columnName)
    {
        JsonNode source = hit.path("_source");
        return source.isObject() ? source.get(columnName) : null;
    }

    private static JsonNode fromFields(JsonNode hit, String columnName)
    {
        JsonNode value = hit.path("fields").path(columnName);
        if (value.isArray()) {
            // Elasticsearch always returns sub-field values as an array
            return value.isEmpty() ? null : value.get(0);
        }
        return value.isMissingNode() ? null : value;
    }

    private static Object convertValue(Type type, String esFieldType, JsonNode node)
    {
        Class<?> javaType = type.getJavaType();
        if (javaType == boolean.class) {
            return convertBoolean(node);
        }
        if (javaType == long.class) {
            return convertLong(type, esFieldType, node);
        }
        if (javaType == double.class) {
            return convertDouble(node);
        }
        if (javaType == Slice.class) {
            return convertSlice(type, node);
        }
        return convertObject(type, node);
    }

    private static boolean convertBoolean(JsonNode node)
    {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asDouble() != 0;
        }
        return Boolean.parseBoolean(node.asText());
    }

    private static long convertLong(Type type, String esFieldType, JsonNode node)
    {
        if (type instanceof TimestampType timestampType && timestampType.isShort()) {
            return convertTimestamp(timestampType, esFieldType, node);
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return (long) node.asDouble();
        }

        String text = node.asText();
        if (type instanceof DecimalType decimalType) {
            return Decimals.encodeShortScaledValue(new BigDecimal(text), decimalType.getScale());
        }
        return Long.parseLong(text);
    }

    private static long convertTimestamp(TimestampType type, String esFieldType, JsonNode node)
    {
        long epochMicros;
        if (node.isNumber()) {
            // Elasticsearch stores epoch based dates as milliseconds, except for date_nanos which uses nanoseconds
            epochMicros = DATE_NANOS_FIELD_TYPE.equals(esFieldType)
                    ? node.asLong() / NANOS_PER_MICRO
                    : node.asLong() * MICROS_PER_MILLI;
        }
        else {
            epochMicros = toEpochMicros(node.asText());
        }
        return Timestamps.round(epochMicros, 6 - type.getPrecision());
    }

    private static double convertDouble(JsonNode node)
    {
        if (node.isNumber()) {
            return node.asDouble();
        }
        return Double.parseDouble(node.asText());
    }

    private static Slice convertSlice(Type type, JsonNode node)
    {
        if (type instanceof VarbinaryType) {
            String value = node.asText();
            try {
                return Slices.wrappedBuffer(Base64.getDecoder().decode(value));
            }
            catch (IllegalArgumentException _) {
                return Slices.utf8Slice(value);
            }
        }

        if (type instanceof DecimalType) {
            throw new IllegalArgumentException("Unsupported wide decimal handling for column type " + type.getDisplayName());
        }

        if (JSON_TYPE_NAME.equals(type.getDisplayName())) {
            return Slices.utf8Slice(node.toString());
        }

        return Slices.utf8Slice(node.asText());
    }

    private static Object convertObject(Type type, JsonNode node)
    {
        if (type instanceof DecimalType decimalType) {
            BigDecimal value = new BigDecimal(node.asText());
            if (decimalType.isShort()) {
                return Decimals.encodeShortScaledValue(value, decimalType.getScale());
            }
            return Decimals.encodeScaledValue(value, decimalType.getScale());
        }
        throw new IllegalArgumentException("Unsupported Trino type: " + type.getDisplayName());
    }

    private static long toEpochMicros(String text)
    {
        if (isDigits(text)) {
            // Elasticsearch stores epoch based dates as milliseconds
            return Long.parseLong(text) * MICROS_PER_MILLI;
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(text);
            return localDateTime.toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND
                    + localDateTime.getNano() / NANOS_PER_MICRO;
        }
        catch (DateTimeParseException _) {
            // Fall through to the offset aware form.
        }

        Instant instant = Instant.parse(text);
        return instant.getEpochSecond() * MICROS_PER_SECOND + instant.getNano() / NANOS_PER_MICRO;
    }

    private static boolean isDigits(String value)
    {
        if (value.isEmpty()) {
            return false;
        }
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
