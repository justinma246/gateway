/**
 * Copyright 2007-2016, Kaazing Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaazing.gateway.transport.wseb.test;

import static org.junit.Assert.fail;
import static org.kaazing.gateway.util.InternalSystemProperty.WSE_SPECIFICATION;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoHandler;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.transport.BridgeServiceFactory;
import org.kaazing.gateway.transport.TransportFactory;
import org.kaazing.gateway.transport.http.HttpConnector;
import org.kaazing.gateway.transport.nio.internal.NioSocketAcceptor;
import org.kaazing.gateway.transport.nio.internal.NioSocketConnector;
import org.kaazing.gateway.transport.wseb.WsebConnector;
import org.kaazing.gateway.util.scheduler.SchedulerProvider;

/**
 * Declaring an instance of this class as a @Rule causes the gateway to be started in process before each test method and stopped
 * after it. The rule can be chained with a K3poRule for use with robot (this causes Robot to be started before the gateway and
 * stopped after it).
 */
public class WsebConnectorRule implements TestRule {

    private ResourceAddressFactory resourceAddressFactory;
    private WsebConnector wseConnector;
    private Properties configuration;

    @Override
    public Statement apply(Statement base, Description description) {
        return new ConnectorStatement(base);
    }

    public WsebConnectorRule() {
        this(new Properties());
    }

    public WsebConnectorRule(Properties configuration) {
        this.configuration = configuration;
    }

    public ConnectFuture connect(final String connect,
                                  final Long wsInactivityTimeout,
                                  IoHandler connectHandler) throws InterruptedException {
        Map<String, Object> connectOptions = new HashMap<>();
        if (wsInactivityTimeout != null) {
            connectOptions.put("inactivityTimeout", wsInactivityTimeout);
        }
        final ResourceAddress connectAddress =
                resourceAddressFactory.newResourceAddress(
                        connect,
                        connectOptions);
        return connect(connectAddress, connectHandler);
    }

    public ConnectFuture connect(final ResourceAddress connectAddress,
                                 IoHandler connectHandler) throws InterruptedException {
        ConnectFuture future = wseConnector.connect(connectAddress, connectHandler, null);

        future.await(TimeUnit.MILLISECONDS.toMillis(3000));

        if (!future.isConnected()) {
            fail("Failed to connect: " + future.getException());
        }
        return future;
    }

    private final class ConnectorStatement extends Statement {

        private final Statement base;
        private Map<String, ?> config = Collections.emptyMap();
        private TransportFactory transportFactory = TransportFactory.newTransportFactory(config);
        private BridgeServiceFactory bridgeServiceFactory = new BridgeServiceFactory(transportFactory);

        private NioSocketConnector tcpConnector;
        private NioSocketAcceptor tcpAcceptor;
        private HttpConnector httpConnector;
        private SchedulerProvider schedulerProvider;

        public ConnectorStatement(Statement base) {
            this.base = base;
        }

        @Override
        public void evaluate() throws Throwable {
            try {
                // Connector setup
                resourceAddressFactory = ResourceAddressFactory.newResourceAddressFactory();
                tcpConnector = (NioSocketConnector)transportFactory.getTransport("tcp").getConnector();
                tcpAcceptor = (NioSocketAcceptor)transportFactory.getTransport("tcp").getAcceptor();
                httpConnector = (HttpConnector)transportFactory.getTransport("http").getConnector();
                wseConnector = (WsebConnector)transportFactory.getTransport("wseb").getConnector();
                schedulerProvider = new SchedulerProvider();

                tcpConnector.setResourceAddressFactory(resourceAddressFactory);
                wseConnector.setResourceAddressFactory(resourceAddressFactory);
                wseConnector.setBridgeServiceFactory(bridgeServiceFactory);
                tcpConnector.setBridgeServiceFactory(bridgeServiceFactory);
                tcpConnector.setTcpAcceptor(tcpAcceptor);

                // Default to spec compliant
                if (configuration.getProperty(WSE_SPECIFICATION.getPropertyName()) == null) {
                    configuration.setProperty(WSE_SPECIFICATION.getPropertyName(), "true");
                }
                wseConnector.setConfiguration(configuration);

                httpConnector.setBridgeServiceFactory(bridgeServiceFactory);
                httpConnector.setResourceAddressFactory(resourceAddressFactory);

                base.evaluate();
            } finally {
                tcpConnector.dispose();
                tcpAcceptor.dispose();
                httpConnector.dispose();
                wseConnector.dispose();
                schedulerProvider.shutdownNow();
            }
        }

    }
}

