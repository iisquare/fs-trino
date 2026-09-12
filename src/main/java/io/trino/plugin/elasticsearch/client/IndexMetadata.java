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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record IndexMetadata(ObjectType schema)
{
    public IndexMetadata
    {
        requireNonNull(schema, "schema is null");
    }

    public record Field(boolean asRawJson, boolean isArray, String name, Type type)
    {
        public Field
        {
            checkArgument(
                    !asRawJson || !isArray,
                    "A column, (%s) cannot be declared as a Trino array and also be rendered as json.",
                    name);
            requireNonNull(name, "name is null");
            requireNonNull(type, "type is null");
        }
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "@type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = DateTimeType.class, name = "date_time"),
            @JsonSubTypes.Type(value = ObjectType.class, name = "object"),
            @JsonSubTypes.Type(value = PrimitiveType.class, name = "primitive"),
            @JsonSubTypes.Type(value = ScaledFloatType.class, name = "scaled_float"),
            // PATCH(fs-trino): multi-field columns, see MultiFieldType below
            @JsonSubTypes.Type(value = MultiFieldType.class, name = "multi_field"),
    })
    public interface Type {}

    public record PrimitiveType(String name)
            implements Type
    {
        public PrimitiveType
        {
            requireNonNull(name, "name is null");
        }
    }

    public record DateTimeType(List<String> formats)
            implements Type
    {
        public DateTimeType
        {
            requireNonNull(formats, "formats is null");
            formats = ImmutableList.copyOf(formats);
        }
    }

    public record ObjectType(List<Field> fields)
            implements Type
    {
        public ObjectType
        {
            requireNonNull(fields, "fields is null");
            fields = ImmutableList.copyOf(fields);
        }
    }

    public record ScaledFloatType(double scale)
            implements Type {}

    // PATCH(fs-trino): marks a column that comes from an Elasticsearch multi-field, i.e. one of the entries of a
    // field's "fields" block (for example the keyword sub-field of a text field, mapped as "name.keyword").
    // Upstream Trino only reads values out of "_source", where multi-fields do not exist, so such columns are
    // added by our patch and their values are fetched through the hits "fields" section instead.
    // Only keyword sub-fields are exposed, which is why this marker carries no further information.
    public record MultiFieldType()
            implements Type {}
}
