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

import io.airlift.configuration.Config;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.net.URI;

public class HttpConfig
{
    private URI baseUri;
    private String catalog;
    private String apiKey = "";
    private String apiKeyHeader = "x-api-key";
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 60;
    private int metadataCacheTtlSeconds = 60;
    private int pageSize = 500;

    @NotNull
    public URI getBaseUri()
    {
        return baseUri;
    }

    @Config("base-uri")
    public HttpConfig setBaseUri(URI baseUri)
    {
        this.baseUri = baseUri;
        return this;
    }

    @NotNull
    public String getCatalog()
    {
        return catalog;
    }

    @Config("catalog")
    public HttpConfig setCatalog(String catalog)
    {
        this.catalog = catalog;
        return this;
    }

    public String getApiKey()
    {
        return apiKey;
    }

    @Config("api-key")
    public HttpConfig setApiKey(String apiKey)
    {
        this.apiKey = apiKey;
        return this;
    }

    @NotNull
    public String getApiKeyHeader()
    {
        return apiKeyHeader;
    }

    @Config("api-key-header")
    public HttpConfig setApiKeyHeader(String apiKeyHeader)
    {
        this.apiKeyHeader = apiKeyHeader;
        return this;
    }

    @Min(1)
    public int getConnectTimeoutSeconds()
    {
        return connectTimeoutSeconds;
    }

    @Config("http.connect-timeout-seconds")
    public HttpConfig setConnectTimeoutSeconds(int connectTimeoutSeconds)
    {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        return this;
    }

    @Min(1)
    public int getRequestTimeoutSeconds()
    {
        return requestTimeoutSeconds;
    }

    @Config("http.request-timeout-seconds")
    public HttpConfig setRequestTimeoutSeconds(int requestTimeoutSeconds)
    {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        return this;
    }

    @Min(0)
    public int getMetadataCacheTtlSeconds()
    {
        return metadataCacheTtlSeconds;
    }

    @Config("metadata-cache-ttl-seconds")
    public HttpConfig setMetadataCacheTtlSeconds(int metadataCacheTtlSeconds)
    {
        this.metadataCacheTtlSeconds = metadataCacheTtlSeconds;
        return this;
    }

    @Min(1)
    public int getPageSize()
    {
        return pageSize;
    }

    @Config("http.page-size")
    public HttpConfig setPageSize(int pageSize)
    {
        this.pageSize = pageSize;
        return this;
    }
}
