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

import com.google.common.collect.ImmutableList;
import io.trino.spi.Plugin;
import io.trino.spi.connector.ConnectorFactory;

/**
 * 本插件的入口。只注册 {@code fs_elasticsearch} 一个连接器。
 * <p>
 * 官方同名的 {@link ElasticsearchPlugin}（注册名 {@code elasticsearch}）随源码一起 vendor 进来，但**故意不在
 * META-INF/services/io.trino.spi.Plugin 里注册**：Trino 镜像自带官方 elasticsearch 插件目录，两个目录注册同名
 * 连接器会让 catalog 的 {@code connector.name=elasticsearch} 指向哪个实现变得不确定。需要官方连接器时用镜像里
 * 那个目录（对拍时正好一个跑官方、一个跑我们的补丁版本）。
 */
public class FsElasticsearchPlugin
        implements Plugin
{
    @Override
    public Iterable<ConnectorFactory> getConnectorFactories()
    {
        return ImmutableList.of(new FsElasticsearchConnectorFactory());
    }
}
