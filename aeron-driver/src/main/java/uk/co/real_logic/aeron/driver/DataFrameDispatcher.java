/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.driver;

import uk.co.real_logic.agrona.collections.BiInt2ObjectMap;
import uk.co.real_logic.agrona.collections.Int2ObjectHashMap;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.common.protocol.SetupFlyweight;
import uk.co.real_logic.aeron.driver.exceptions.UnknownSubscriptionException;

import java.net.InetSocketAddress;

/**
 * Handling of dispatching data frames to {@link DriverConnection}s streams.
 *
 * All methods should be called via {@link Receiver} thread
 */
public class DataFrameDispatcher implements DataFrameHandler, SetupFrameHandler
{
    private static final Integer PENDING_SETUP_FRAME = 1;
    private static final Integer INIT_IN_PROGRESS = 2;

    private final BiInt2ObjectMap<Integer> initialisationInProgressMap = new BiInt2ObjectMap<>();
    private final Int2ObjectHashMap<Int2ObjectHashMap<DriverConnection>> connectionsByStreamIdMap = new Int2ObjectHashMap<>();
    private final DriverConductorProxy conductorProxy;
    private final ReceiveChannelEndpoint channelEndpoint;

    public DataFrameDispatcher(final DriverConductorProxy conductorProxy, final ReceiveChannelEndpoint channelEndpoint)
    {
        this.conductorProxy = conductorProxy;
        this.channelEndpoint = channelEndpoint;
    }

    public void addSubscription(final int streamId)
    {
        if (null == connectionsByStreamIdMap.get(streamId))
        {
            connectionsByStreamIdMap.put(streamId, new Int2ObjectHashMap<>());
        }
    }

    public void onRemoveSubscription(final int streamId)
    {
        final Int2ObjectHashMap<DriverConnection> connectionBySessionIdMap = connectionsByStreamIdMap.remove(streamId);
        if (null == connectionBySessionIdMap)
        {
            throw new UnknownSubscriptionException("No subscription registered on stream " + streamId);
        }

        for (final DriverConnection connection : connectionBySessionIdMap.values())
        {
            connection.disableStatusMessages();
            connection.disableScanForGaps();
        }
    }

    public void addConnection(final DriverConnection connection)
    {
        final int sessionId = connection.sessionId();
        final int streamId = connection.streamId();

        final Int2ObjectHashMap<DriverConnection> connectionBySessionIdMap = connectionsByStreamIdMap.get(streamId);
        if (null == connectionBySessionIdMap)
        {
            throw new IllegalStateException("No subscription registered on stream " + streamId);
        }

        connectionBySessionIdMap.put(sessionId, connection);
        initialisationInProgressMap.remove(sessionId, streamId);

        connection.enableStatusMessages();
    }

    public void removeConnection(final DriverConnection connection)
    {
        final int sessionId = connection.sessionId();
        final int streamId = connection.streamId();

        final Int2ObjectHashMap<DriverConnection> connectionBySessionIdMap = connectionsByStreamIdMap.get(streamId);
        if (null != connectionBySessionIdMap)
        {
            connection.disableStatusMessages();
            connectionBySessionIdMap.remove(sessionId);
            initialisationInProgressMap.remove(sessionId, streamId);
        }
    }

    public void removePendingSetup(final int sessionId, final int streamId)
    {
        if (PENDING_SETUP_FRAME.equals(initialisationInProgressMap.get(sessionId, streamId)))
        {
            initialisationInProgressMap.remove(sessionId, streamId);
        }
    }

    public int onFrame(
        final DataHeaderFlyweight header, final UnsafeBuffer buffer, final int length, final InetSocketAddress srcAddress)
    {
        final int streamId = header.streamId();
        final Int2ObjectHashMap<DriverConnection> connectionBySessionIdMap = connectionsByStreamIdMap.get(streamId);

        if (null != connectionBySessionIdMap)
        {
            final int sessionId = header.sessionId();
            final int termId = header.termId();
            final DriverConnection connection = connectionBySessionIdMap.get(sessionId);

            if (null != connection)
            {
                return connection.insertIntoTerm(termId, header.termOffset(), buffer, length);
            }
            else if (null == initialisationInProgressMap.get(sessionId, streamId))
            {
                elicitSetupFromSource(srcAddress, streamId, sessionId);
            }
        }

        return 0;
    }

    public void onFrame(
        final SetupFlyweight header, final UnsafeBuffer buffer, final int length, final InetSocketAddress srcAddress)
    {
        final int streamId = header.streamId();
        final Int2ObjectHashMap<DriverConnection> connectionBySessionIdMap = connectionsByStreamIdMap.get(streamId);

        if (null != connectionBySessionIdMap)
        {
            final int sessionId = header.sessionId();
            final int termId = header.termId();
            final DriverConnection connection = connectionBySessionIdMap.get(sessionId);

            if (null == connection && !INIT_IN_PROGRESS.equals(initialisationInProgressMap.get(sessionId, streamId)))
            {
                createConnection(
                    srcAddress, streamId, sessionId, termId, header.termOffset(), header.termLength(), header.mtuLength());
            }
        }
    }

    private void elicitSetupFromSource(final InetSocketAddress srcAddress, final int streamId, final int sessionId)
    {
        final UdpChannelTransport transport = channelEndpoint.transport();
        final InetSocketAddress controlAddress =
            transport.isMulticast() ? transport.udpChannel().remoteControl() : srcAddress;

        initialisationInProgressMap.put(sessionId, streamId, PENDING_SETUP_FRAME);
        conductorProxy.elicitSetupFromSource(sessionId, streamId, controlAddress, channelEndpoint);
    }

    private void createConnection(
        final InetSocketAddress srcAddress,
        final int streamId,
        final int sessionId,
        final int termId,
        final int termOffset,
        final int termLength,
        final int mtuLength)
    {
        final UdpChannelTransport transport = channelEndpoint.transport();
        final InetSocketAddress controlAddress =
            transport.isMulticast() ? transport.udpChannel().remoteControl() : srcAddress;

        initialisationInProgressMap.put(sessionId, streamId, INIT_IN_PROGRESS);
        conductorProxy.createConnection(
            sessionId, streamId, termId, termOffset, termLength, mtuLength, controlAddress, srcAddress, channelEndpoint);
    }
}
