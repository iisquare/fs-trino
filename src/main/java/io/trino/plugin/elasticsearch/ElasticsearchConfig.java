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

import io.airlift.configuration.Config;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.net.URI;

public class ElasticsearchConfig
{
    private URI elasticsearchUri;
    private String username = "";
    private String password = "";
    private boolean tlsVerify = true;
    private String defaultSchemaName = "es";
    private int pageSize = 500;
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 60;
    private int metadataCacheTtlSeconds = 60;
    private int pitKeepAliveSeconds = 60;

    @NotNull
    public URI getElasticsearchUri()
    {
        return elasticsearchUri;
    }

    @Config("elasticsearch.uri")
    public ElasticsearchConfig setElasticsearchUri(URI elasticsearchUri)
    {
        this.elasticsearchUri = elasticsearchUri;
        return this;
    }

    @NotNull
    public String getUsername()
    {
        return username;
    }

    @Config("elasticsearch.username")
    public ElasticsearchConfig setUsername(String username)
    {
        this.username = username;
        return this;
    }

    @NotNull
    public String getPassword()
    {
        return password;
    }

    @Config("elasticsearch.password")
    public ElasticsearchConfig setPassword(String password)
    {
        this.password = password;
        return this;
    }

    public boolean isTlsVerify()
    {
        return tlsVerify;
    }

    @Config("elasticsearch.tls.verify")
    public ElasticsearchConfig setTlsVerify(boolean tlsVerify)
    {
        this.tlsVerify = tlsVerify;
        return this;
    }

    @NotNull
    public String getDefaultSchemaName()
    {
        return defaultSchemaName;
    }

    @Config("elasticsearch.default-schema-name")
    public ElasticsearchConfig setDefaultSchemaName(String defaultSchemaName)
    {
        this.defaultSchemaName = defaultSchemaName;
        return this;
    }

    @Min(1)
    public int getPageSize()
    {
        return pageSize;
    }

    @Config("elasticsearch.page-size")
    public ElasticsearchConfig setPageSize(int pageSize)
    {
        this.pageSize = pageSize;
        return this;
    }

    @Min(1)
    public int getConnectTimeoutSeconds()
    {
        return connectTimeoutSeconds;
    }

    @Config("elasticsearch.connect-timeout-seconds")
    public ElasticsearchConfig setConnectTimeoutSeconds(int connectTimeoutSeconds)
    {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        return this;
    }

    @Min(1)
    public int getRequestTimeoutSeconds()
    {
        return requestTimeoutSeconds;
    }

    @Config("elasticsearch.request-timeout-seconds")
    public ElasticsearchConfig setRequestTimeoutSeconds(int requestTimeoutSeconds)
    {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        return this;
    }

    @Min(0)
    public int getMetadataCacheTtlSeconds()
    {
        return metadataCacheTtlSeconds;
    }

    @Config("elasticsearch.metadata-cache-ttl-seconds")
    public ElasticsearchConfig setMetadataCacheTtlSeconds(int metadataCacheTtlSeconds)
    {
        this.metadataCacheTtlSeconds = metadataCacheTtlSeconds;
        return this;
    }

    @Min(1)
    public int getPitKeepAliveSeconds()
    {
        return pitKeepAliveSeconds;
    }

    @Config("elasticsearch.pit-keep-alive-seconds")
    public ElasticsearchConfig setPitKeepAliveSeconds(int pitKeepAliveSeconds)
    {
        this.pitKeepAliveSeconds = pitKeepAliveSeconds;
        return this;
    }
}
