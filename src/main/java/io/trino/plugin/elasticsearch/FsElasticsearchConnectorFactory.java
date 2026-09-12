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

import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorContext;
import io.trino.spi.connector.ConnectorFactory;

import java.util.Map;

/**
 * 官方 Elasticsearch 连接器，注册名换成 {@code fs_elasticsearch}。
 * <p>
 * 除了名字以外全部委托给 {@link ElasticsearchConnectorFactory}：同一个插件目录里既有官方实现，也有我们对
 * keyword 子字段（multi-field）的补丁，具体见 README 的补丁清单。
 */
public class FsElasticsearchConnectorFactory
        implements ConnectorFactory
{
    private final ElasticsearchConnectorFactory delegate = new ElasticsearchConnectorFactory();

    @Override
    public String getName()
    {
        return "fs_elasticsearch";
    }

    @Override
    public Connector create(String catalogName, Map<String, String> config, ConnectorContext context)
    {
        return delegate.create(catalogName, config, context);
    }
}
