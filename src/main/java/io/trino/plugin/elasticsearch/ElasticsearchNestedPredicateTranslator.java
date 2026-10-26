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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.trino.plugin.elasticsearch.client.IndexMetadata;
import io.trino.plugin.elasticsearch.client.IndexMetadata.DateTimeType;
import io.trino.plugin.elasticsearch.client.IndexMetadata.MultiFieldType;
import io.trino.plugin.elasticsearch.client.IndexMetadata.NestedType;
import io.trino.plugin.elasticsearch.client.IndexMetadata.ObjectType;
import io.trino.plugin.elasticsearch.client.IndexMetadata.PrimitiveType;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.FieldDereference;
import io.trino.spi.expression.Lambda;
import io.trino.spi.expression.Variable;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.expression.StandardFunctions.AND_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.OR_FUNCTION_NAME;
import static java.util.Locale.ENGLISH;

final class ElasticsearchNestedPredicateTranslator
{
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ElasticsearchNestedPredicateTranslator() {}

    static Optional<ElasticsearchNestedQuery> translateAnyMatch(
            ConnectorExpression expression,
            Map<String, ColumnHandle> assignments)
    {
        if (!(expression instanceof Call call) || !"any_match".equals(call.getFunctionName().getName())) {
            return Optional.empty();
        }
        if (call.getArguments().size() != 2 || !(call.getArguments().get(0) instanceof Variable nestedVariable)) {
            return Optional.empty();
        }
        if (!(call.getArguments().get(1) instanceof Lambda lambda) || lambda.getArguments().size() != 1) {
            return Optional.empty();
        }
        if (!(assignments.get(nestedVariable.getName()) instanceof ElasticsearchColumnHandle column)) {
            return Optional.empty();
        }
        if (!(column.type() instanceof ArrayType arrayType) || !(arrayType.getElementType() instanceof RowType rowType)) {
            return Optional.empty();
        }
        if (!(column.elasticsearchType() instanceof NestedType nestedType) || !arrayType.equals(nestedVariable.getType())) {
            return Optional.empty();
        }

        Variable rowArgument = lambda.getArguments().get(0);
        if (!rowType.equals(rowArgument.getType())) {
            return Optional.empty();
        }

        FieldContext root = new FieldContext(column.name(), rowType, nestedType);
        Optional<JsonNode> predicate = translateBoolean(lambda.getBody(), rowArgument.getName(), root);
        return predicate.map(query -> new ElasticsearchNestedQuery(column.name(), query.toString()));
    }

    private static Optional<JsonNode> translateBoolean(
            ConnectorExpression expression,
            String rowArgument,
            FieldContext root)
    {
        if (expression instanceof FieldDereference) {
            Optional<FieldContext> field = resolveField(expression, rowArgument, root);
            if (field.isPresent() && isBoolean(field.get().elasticsearchType())) {
                return Optional.of(ElasticsearchQueryBuilder.termQuery(field.get().path(), true));
            }
            return Optional.empty();
        }

        if (!(expression instanceof Call call)) {
            return Optional.empty();
        }

        if (call.getFunctionName().equals(AND_FUNCTION_NAME) || call.getFunctionName().equals(OR_FUNCTION_NAME)) {
            List<JsonNode> children = new ArrayList<>();
            for (ConnectorExpression argument : call.getArguments()) {
                Optional<JsonNode> translated = translateBoolean(argument, rowArgument, root);
                if (translated.isEmpty()) {
                    return Optional.empty();
                }
                children.add(translated.get());
            }
            if (children.isEmpty()) {
                return Optional.empty();
            }
            if (children.size() == 1) {
                return Optional.of(children.getFirst());
            }
            ObjectNode bool = JSON.objectNode();
            ArrayNode clauses = JSON.arrayNode();
            children.forEach(clauses::add);
            if (call.getFunctionName().equals(AND_FUNCTION_NAME)) {
                bool.set("filter", clauses);
            }
            else {
                bool.set("should", clauses);
                bool.put("minimum_should_match", 1);
            }
            return Optional.of(JSON.objectNode().set("bool", bool));
        }

        return translateComparison(call, rowArgument, root);
    }

    private static Optional<JsonNode> translateComparison(
            Call call,
            String rowArgument,
            FieldContext root)
    {
        String function = call.getFunctionName().getName();
        if (!isComparison(function) || call.getArguments().size() != 2) {
            return Optional.empty();
        }

        Optional<FieldContext> field = resolveField(call.getArguments().get(0), rowArgument, root);
        Optional<Constant> constant = asConstant(call.getArguments().get(1));
        if (field.isEmpty() || constant.isEmpty()) {
            field = resolveField(call.getArguments().get(1), rowArgument, root);
            constant = asConstant(call.getArguments().get(0));
            function = reverse(function);
        }
        if (field.isEmpty() || constant.isEmpty() || !supportsPredicates(field.get().elasticsearchType())) {
            return Optional.empty();
        }

        try {
            Object value = ElasticsearchQueryBuilder.getValue(field.get().trinoType(), constant.get().getValue());
            String fieldPath = field.get().path();
            if (EQUAL_OPERATOR_FUNCTION_NAME.getName().equals(function)) {
                return Optional.of(ElasticsearchQueryBuilder.termQuery(fieldPath, value));
            }
            if (NOT_EQUAL_OPERATOR_FUNCTION_NAME.getName().equals(function)) {
                return Optional.of(notEqualQuery(fieldPath, value));
            }

            ObjectNode range = JSON.objectNode();
            if (function.equals(LESS_THAN_OPERATOR_FUNCTION_NAME.getName())) {
                range.set("lt", ElasticsearchQueryBuilder.toJsonValue(value));
            }
            else if (function.equals(LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName())) {
                range.set("lte", ElasticsearchQueryBuilder.toJsonValue(value));
            }
            else if (function.equals(GREATER_THAN_OPERATOR_FUNCTION_NAME.getName())) {
                range.set("gt", ElasticsearchQueryBuilder.toJsonValue(value));
            }
            else if (function.equals(GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName())) {
                range.set("gte", ElasticsearchQueryBuilder.toJsonValue(value));
            }
            else {
                return Optional.empty();
            }
            return Optional.of(JSON.objectNode().set(
                    "range",
                    JSON.objectNode().set(fieldPath, range)));
        }
        catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static Optional<FieldContext> resolveField(
            ConnectorExpression expression,
            String rowArgument,
            FieldContext root)
    {
        if (expression instanceof Variable variable && variable.getName().equals(rowArgument)) {
            return Optional.of(root);
        }
        if (!(expression instanceof FieldDereference dereference)) {
            return Optional.empty();
        }

        Optional<FieldContext> parent = resolveField(dereference.getTarget(), rowArgument, root);
        if (parent.isEmpty() ||
                !(parent.get().trinoType() instanceof RowType rowType) ||
                !(parent.get().elasticsearchType() instanceof ObjectType || parent.get().elasticsearchType() instanceof NestedType)) {
            return Optional.empty();
        }

        List<RowType.Field> rowFields = rowType.getFields();
        if (dereference.getField() < 0 || dereference.getField() >= rowFields.size()) {
            return Optional.empty();
        }
        RowType.Field rowField = rowFields.get(dereference.getField());
        if (rowField.getName().isEmpty()) {
            return Optional.empty();
        }

        Optional<IndexMetadata.Field> elasticsearchField = fields(parent.get().elasticsearchType()).stream()
                .filter(field -> field.name().equals(rowField.getName().get()))
                .findFirst();
        if (elasticsearchField.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new FieldContext(
                parent.get().path() + "." + rowField.getName().get(),
                rowField.getType(),
                elasticsearchField.get().type()));
    }

    private static List<IndexMetadata.Field> fields(IndexMetadata.Type type)
    {
        if (type instanceof ObjectType objectType) {
            return objectType.fields();
        }
        if (type instanceof NestedType nestedType) {
            return nestedType.fields();
        }
        return List.of();
    }

    private static Optional<Constant> asConstant(ConnectorExpression expression)
    {
        if (expression instanceof Constant constant) {
            return Optional.of(constant);
        }
        return Optional.empty();
    }

    private static boolean isComparison(String function)
    {
        return function.equals(EQUAL_OPERATOR_FUNCTION_NAME.getName()) ||
                function.equals(NOT_EQUAL_OPERATOR_FUNCTION_NAME.getName()) ||
                function.equals(LESS_THAN_OPERATOR_FUNCTION_NAME.getName()) ||
                function.equals(LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName()) ||
                function.equals(GREATER_THAN_OPERATOR_FUNCTION_NAME.getName()) ||
                function.equals(GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName());
    }

    private static String reverse(String function)
    {
        if (function.equals(LESS_THAN_OPERATOR_FUNCTION_NAME.getName())) {
            return GREATER_THAN_OPERATOR_FUNCTION_NAME.getName();
        }
        if (function.equals(LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName())) {
            return GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName();
        }
        if (function.equals(GREATER_THAN_OPERATOR_FUNCTION_NAME.getName())) {
            return LESS_THAN_OPERATOR_FUNCTION_NAME.getName();
        }
        if (function.equals(GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName())) {
            return LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME.getName();
        }
        return function;
    }

    private static boolean supportsPredicates(IndexMetadata.Type type)
    {
        if (type instanceof MultiFieldType || type instanceof DateTimeType) {
            return true;
        }
        if (!(type instanceof PrimitiveType primitiveType)) {
            return false;
        }
        return switch (primitiveType.name().toLowerCase(ENGLISH)) {
            case "keyword", "long", "integer", "short", "byte", "double", "float", "boolean" -> true;
            default -> false;
        };
    }

    private static boolean isBoolean(IndexMetadata.Type type)
    {
        return type instanceof PrimitiveType primitiveType && "boolean".equals(primitiveType.name().toLowerCase(ENGLISH));
    }

    private static ObjectNode notEqualQuery(String field, Object value)
    {
        ObjectNode bool = JSON.objectNode();
        bool.set("filter", JSON.arrayNode().add(ElasticsearchQueryBuilder.existsQuery(field)));
        bool.set("must_not", JSON.arrayNode().add(ElasticsearchQueryBuilder.termQuery(field, value)));
        return JSON.objectNode().set("bool", bool);
    }

    private record FieldContext(String path, Type trinoType, IndexMetadata.Type elasticsearchType) {}
}
