/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
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
package org.neo4j.driver.internal.handlers;

import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Promise;

import java.util.Map;

import org.neo4j.driver.internal.async.inbound.InboundMessageDispatcher;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.util.Clock;
import org.neo4j.driver.v1.Value;

import static org.neo4j.driver.internal.async.ChannelAttributes.setLastUsedTimestamp;

public class ResetResponseHandler implements ResponseHandler
{
    private final Channel channel;
    private final ChannelPool pool;
    private final InboundMessageDispatcher messageDispatcher;
    private final Clock clock;
    private final Promise<Void> releasePromise;

    public ResetResponseHandler( Channel channel, ChannelPool pool, InboundMessageDispatcher messageDispatcher,
            Clock clock )
    {
        this( channel, pool, messageDispatcher, clock, null );
    }

    public ResetResponseHandler( Channel channel, ChannelPool pool, InboundMessageDispatcher messageDispatcher,
            Clock clock, Promise<Void> releasePromise )
    {
        this.channel = channel;
        this.pool = pool;
        this.messageDispatcher = messageDispatcher;
        this.clock = clock;
        this.releasePromise = releasePromise;
    }

    @Override
    public void onSuccess( Map<String,Value> metadata )
    {
        releaseChannel();
    }

    @Override
    public void onFailure( Throwable error )
    {
        releaseChannel();
    }

    @Override
    public void onRecord( Value[] fields )
    {
        throw new UnsupportedOperationException();
    }

    private void releaseChannel()
    {
        messageDispatcher.unMuteAckFailure();
        setLastUsedTimestamp( channel, clock.millis() );

        if ( releasePromise == null )
        {
            pool.release( channel );
        }
        else
        {
            pool.release( channel, releasePromise );
        }
    }
}
