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

/**
 * Where the value of a column is read from in an Elasticsearch search hit.
 */
public enum ElasticsearchColumnSource
{
    /**
     * A mapped field, read from the {@code _source} of the hit.
     */
    MAPPED_FIELD,

    /**
     * A multi-field sub-field, for example the {@code keyword} entry of a {@code text} field. Sub-fields are not
     * part of {@code _source}, so they are read from the {@code fields} section of the hit.
     */
    MULTI_FIELD,

    /**
     * The document id, read from {@code _id}.
     */
    DOCUMENT_ID,

    /**
     * The whole document source, read from {@code _source}.
     */
    DOCUMENT_SOURCE,
}
