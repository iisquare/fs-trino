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
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Timestamps;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public final class HttpRecordCursor
        implements RecordCursor
{
    private static final int NANOS_PER_MICRO = 1_000;
    private static final int PICOS_PER_NANO = 1_000;

    private final HttpClient client;
    private final HttpConfig config;
    private final String schemaName;
    private final String tableName;
    private final List<HttpColumnHandle> columnHandles;

    private Iterator<JsonNode> rows;
    private OptionalLong nextOffset;
    private Object[] currentRow;
    private boolean closed;

    public HttpRecordCursor(
            HttpClient client,
            HttpConfig config,
            HttpSplit split,
            List<HttpColumnHandle> columnHandles)
    {
        this.client = requireNonNull(client, "client is null");
        this.config = requireNonNull(config, "config is null");
        this.schemaName = requireNonNull(split.getSchemaName(), "schemaName is null");
        this.tableName = requireNonNull(split.getTableName(), "tableName is null");
        this.columnHandles = requireNonNull(columnHandles, "columnHandles is null");
        this.rows = List.<JsonNode>of().iterator();
        this.nextOffset = OptionalLong.of(0);
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

            if (nextOffset.isEmpty()) {
                currentRow = null;
                return false;
            }

            long requestedOffset = nextOffset.getAsLong();
            HttpDataPage page;
            try {
                page = client.fetchPage(schemaName, tableName, requestedOffset, config.getPageSize());
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            rows = page.getRows().iterator();
            nextOffset = page.getNextOffset();

            if (!rows.hasNext() && (nextOffset.isEmpty() || nextOffset.getAsLong() == requestedOffset)) {
                currentRow = null;
                return false;
            }
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
    }

    private Object[] convertRow(JsonNode row)
    {
        if (row == null || !row.isObject()) {
            throw new IllegalStateException("Data row must be a JSON object");
        }

        Object[] values = new Object[columnHandles.size()];
        for (int i = 0; i < columnHandles.size(); i++) {
            HttpColumnHandle handle = columnHandles.get(i);
            JsonNode field = row.get(handle.getColumnName());
            values[i] = field == null || field.isNull()
                    ? null
                    : convertValue(handle.getColumnType(), field);
        }
        return values;
    }

    private static Object convertValue(Type type, JsonNode node)
    {
        Class<?> javaType = type.getJavaType();
        if (javaType == boolean.class) {
            return node.asBoolean();
        }
        if (javaType == long.class) {
            return convertLong(type, node);
        }
        if (javaType == double.class) {
            return convertDouble(node);
        }
        if (javaType == Slice.class) {
            return convertSlice(type, node);
        }
        return convertObject(type, node);
    }

    private static long convertLong(Type type, JsonNode node)
    {
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return (long) node.asDouble();
        }

        String text = node.asText();
        if (type instanceof DateType) {
            return LocalDate.parse(text).toEpochDay();
        }
        if (type instanceof TimeType timeType) {
            long picos = LocalTime.parse(text).toNanoOfDay() * PICOS_PER_NANO;
            return Timestamps.round(picos, 12 - timeType.getPrecision());
        }
        if (type instanceof TimestampType timestampType && timestampType.isShort()) {
            long epochMicros = parseTimestampMicros(text);
            return Timestamps.round(epochMicros, 6 - timestampType.getPrecision());
        }
        if (type instanceof DecimalType decimalType && decimalType.isShort()) {
            return Decimals.encodeShortScaledValue(new BigDecimal(text), decimalType.getScale());
        }
        return Long.parseLong(text);
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
            if (node.isTextual()) {
                String value = node.asText();
                if (value.startsWith("0x") || value.startsWith("0X")) {
                    return Slices.wrappedBuffer(Hex.decodeHex(value.substring(2)));
                }
                try {
                    return Slices.wrappedBuffer(Base64.getDecoder().decode(value));
                }
                catch (IllegalArgumentException _) {
                    return Slices.utf8Slice(value);
                }
            }
            return Slices.utf8Slice(node.asText());
        }

        if (node.isTextual()) {
            return Slices.utf8Slice(node.asText());
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
        if (type instanceof TimestampType timestampType && !timestampType.isShort()) {
            throw new IllegalArgumentException(format(
                    "Unsupported timestamp precision %s. The HTTP API connector currently supports timestamp(0) to timestamp(6)",
                    timestampType.getPrecision()));
        }
        throw new IllegalArgumentException("Unsupported Trino type: " + type.getDisplayName());
    }

    private static long parseTimestampMicros(String text)
    {
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException _) {
            // Try the standard local date-time and instant forms.
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(text);
            long epochMicros = localDateTime.toEpochSecond(ZoneOffset.UTC) * 1_000_000L
                    + localDateTime.getNano() / NANOS_PER_MICRO;
            return epochMicros;
        }
        catch (RuntimeException _) {
            // Fall through.
        }

        Instant instant = Instant.parse(text);
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / NANOS_PER_MICRO;
    }

    private static final class Hex
    {
        private Hex() {}

        static byte[] decodeHex(String value)
        {
            int length = value.length();
            byte[] data = new byte[length / 2];
            for (int i = 0; i < length; i += 2) {
                data[i / 2] = (byte) ((Character.digit(value.charAt(i), 16) << 4)
                        + Character.digit(value.charAt(i + 1), 16));
            }
            return data;
        }
    }
}
