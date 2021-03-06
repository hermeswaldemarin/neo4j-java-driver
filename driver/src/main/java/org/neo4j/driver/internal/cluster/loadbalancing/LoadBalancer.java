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
package org.neo4j.driver.internal.cluster.loadbalancing;

import io.netty.util.concurrent.EventExecutorGroup;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.neo4j.driver.internal.RoutingErrorHandler;
import org.neo4j.driver.internal.async.BoltServerAddress;
import org.neo4j.driver.internal.async.RoutingConnection;
import org.neo4j.driver.internal.cluster.AddressSet;
import org.neo4j.driver.internal.cluster.ClusterComposition;
import org.neo4j.driver.internal.cluster.ClusterCompositionProvider;
import org.neo4j.driver.internal.cluster.ClusterRoutingTable;
import org.neo4j.driver.internal.cluster.DnsResolver;
import org.neo4j.driver.internal.cluster.Rediscovery;
import org.neo4j.driver.internal.cluster.RoutingProcedureClusterCompositionProvider;
import org.neo4j.driver.internal.cluster.RoutingSettings;
import org.neo4j.driver.internal.cluster.RoutingTable;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ConnectionPool;
import org.neo4j.driver.internal.spi.ConnectionProvider;
import org.neo4j.driver.internal.util.Clock;
import org.neo4j.driver.internal.util.Futures;
import org.neo4j.driver.v1.AccessMode;
import org.neo4j.driver.v1.Logger;
import org.neo4j.driver.v1.Logging;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;
import org.neo4j.driver.v1.exceptions.SessionExpiredException;

import static java.util.concurrent.CompletableFuture.completedFuture;

public class LoadBalancer implements ConnectionProvider, RoutingErrorHandler
{
    private static final String LOAD_BALANCER_LOG_NAME = "LoadBalancer";

    private final ConnectionPool connectionPool;
    private final RoutingTable routingTable;
    private final Rediscovery rediscovery;
    private final LoadBalancingStrategy loadBalancingStrategy;
    private final EventExecutorGroup eventExecutorGroup;
    private final Logger log;

    private CompletableFuture<RoutingTable> refreshRoutingTableFuture;

    public LoadBalancer( BoltServerAddress initialRouter, RoutingSettings settings, ConnectionPool connectionPool,
            EventExecutorGroup eventExecutorGroup, Clock clock, Logging logging,
            LoadBalancingStrategy loadBalancingStrategy )
    {
        this( connectionPool, new ClusterRoutingTable( clock, initialRouter ),
                createRediscovery( initialRouter, settings, eventExecutorGroup, clock, logging ),
                loadBalancerLogger( logging ), loadBalancingStrategy, eventExecutorGroup );
    }

    // Used only in testing
    public LoadBalancer( ConnectionPool connectionPool, RoutingTable routingTable, Rediscovery rediscovery,
            EventExecutorGroup eventExecutorGroup, Logging logging )
    {
        this( connectionPool, routingTable, rediscovery, loadBalancerLogger( logging ),
                new LeastConnectedLoadBalancingStrategy( connectionPool, logging ),
                eventExecutorGroup );
    }

    private LoadBalancer( ConnectionPool connectionPool, RoutingTable routingTable, Rediscovery rediscovery,
            Logger log, LoadBalancingStrategy loadBalancingStrategy, EventExecutorGroup eventExecutorGroup )
    {
        this.connectionPool = connectionPool;
        this.routingTable = routingTable;
        this.rediscovery = rediscovery;
        this.loadBalancingStrategy = loadBalancingStrategy;
        this.eventExecutorGroup = eventExecutorGroup;
        this.log = log;
    }

    @Override
    public CompletionStage<Connection> acquireConnection( AccessMode mode )
    {
        return freshRoutingTable( mode )
                .thenCompose( routingTable -> acquire( mode, routingTable ) )
                .thenApply( connection -> new RoutingConnection( connection, mode, this ) );
    }

    @Override
    public CompletionStage<Void> verifyConnectivity()
    {
        return freshRoutingTable( AccessMode.READ ).thenApply( routingTable -> null );
    }

    @Override
    public void onConnectionFailure( BoltServerAddress address )
    {
        forget( address );
    }

    @Override
    public void onWriteFailure( BoltServerAddress address )
    {
        routingTable.removeWriter( address );
    }

    @Override
    public CompletionStage<Void> close()
    {
        return connectionPool.close();
    }

    private synchronized void forget( BoltServerAddress address )
    {
        // First remove from the load balancer, to prevent concurrent threads from making connections to them.
        routingTable.forget( address );
        // drop all current connections to the address
        connectionPool.purge( address );
    }

    private synchronized CompletionStage<RoutingTable> freshRoutingTable( AccessMode mode )
    {
        if ( refreshRoutingTableFuture != null )
        {
            // refresh is already happening concurrently, just use it's result
            return refreshRoutingTableFuture;
        }
        else if ( routingTable.isStaleFor( mode ) )
        {
            // existing routing table is not fresh and should be updated
            log.info( "Routing information is stale. %s", routingTable );

            CompletableFuture<RoutingTable> resultFuture = new CompletableFuture<>();
            refreshRoutingTableFuture = resultFuture;

            rediscovery.lookupClusterComposition( routingTable, connectionPool )
                    .whenComplete( ( composition, completionError ) ->
                    {
                        Throwable error = Futures.completionErrorCause( completionError );
                        if ( error != null )
                        {
                            clusterCompositionLookupFailed( error );
                        }
                        else
                        {
                            freshClusterCompositionFetched( composition );
                        }
                    } );

            return resultFuture;
        }
        else
        {
            // existing routing table is fresh, use it
            return completedFuture( routingTable );
        }
    }

    private synchronized void freshClusterCompositionFetched( ClusterComposition composition )
    {
        Set<BoltServerAddress> removed = routingTable.update( composition );

        for ( BoltServerAddress address : removed )
        {
            connectionPool.purge( address );
        }

        log.info( "Refreshed routing information. %s", routingTable );

        CompletableFuture<RoutingTable> routingTableFuture = refreshRoutingTableFuture;
        refreshRoutingTableFuture = null;
        routingTableFuture.complete( routingTable );
    }

    private synchronized void clusterCompositionLookupFailed( Throwable error )
    {
        CompletableFuture<RoutingTable> routingTableFuture = refreshRoutingTableFuture;
        refreshRoutingTableFuture = null;
        routingTableFuture.completeExceptionally( error );
    }

    private CompletionStage<Connection> acquire( AccessMode mode, RoutingTable routingTable )
    {
        AddressSet addresses = addressSet( mode, routingTable );
        CompletableFuture<Connection> result = new CompletableFuture<>();
        acquire( mode, addresses, result );
        return result;
    }

    private void acquire( AccessMode mode, AddressSet addresses, CompletableFuture<Connection> result )
    {
        BoltServerAddress address = selectAddress( mode, addresses );

        if ( address == null )
        {
            result.completeExceptionally( new SessionExpiredException(
                    "Failed to obtain connection towards " + mode + " server. " +
                    "Known routing table is: " + routingTable ) );
            return;
        }

        connectionPool.acquire( address ).whenComplete( ( connection, completionError ) ->
        {
            Throwable error = Futures.completionErrorCause( completionError );
            if ( error != null )
            {
                if ( error instanceof ServiceUnavailableException )
                {
                    log.error( "Failed to obtain a connection towards address " + address, error );
                    forget( address );
                    eventExecutorGroup.next().execute( () -> acquire( mode, addresses, result ) );
                }
                else
                {
                    result.completeExceptionally( error );
                }
            }
            else
            {
                result.complete( connection );
            }
        } );
    }

    private static AddressSet addressSet( AccessMode mode, RoutingTable routingTable )
    {
        switch ( mode )
        {
        case READ:
            return routingTable.readers();
        case WRITE:
            return routingTable.writers();
        default:
            throw unknownMode( mode );
        }
    }

    private BoltServerAddress selectAddress( AccessMode mode, AddressSet servers )
    {
        BoltServerAddress[] addresses = servers.toArray();

        switch ( mode )
        {
        case READ:
            return loadBalancingStrategy.selectReader( addresses );
        case WRITE:
            return loadBalancingStrategy.selectWriter( addresses );
        default:
            throw unknownMode( mode );
        }
    }

    private static Rediscovery createRediscovery( BoltServerAddress initialRouter, RoutingSettings settings,
            EventExecutorGroup eventExecutorGroup, Clock clock, Logging logging )
    {
        Logger log = loadBalancerLogger( logging );
        ClusterCompositionProvider clusterCompositionProvider =
                new RoutingProcedureClusterCompositionProvider( clock, log, settings );
        return new Rediscovery( initialRouter, settings, clusterCompositionProvider, eventExecutorGroup,
                new DnsResolver( log ), log );
    }

    private static Logger loadBalancerLogger( Logging logging )
    {
        return logging.getLog( LOAD_BALANCER_LOG_NAME );
    }

    private static RuntimeException unknownMode( AccessMode mode )
    {
        return new IllegalArgumentException( "Mode '" + mode + "' is not supported" );
    }
}
