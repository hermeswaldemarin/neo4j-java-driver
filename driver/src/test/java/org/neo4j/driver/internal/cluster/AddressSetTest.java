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
package org.neo4j.driver.internal.cluster;

import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.neo4j.driver.internal.async.BoltServerAddress;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AddressSetTest
{
    @Test
    public void shouldPreserveOrderWhenAdding() throws Exception
    {
        // given
        Set<BoltServerAddress> servers = addresses( "one", "two", "tre" );

        AddressSet set = new AddressSet();
        set.update( servers, new HashSet<BoltServerAddress>() );

        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "one" ),
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" )}, set.toArray() );

        // when
        servers.add( new BoltServerAddress( "fyr" ) );
        set.update( servers, new HashSet<BoltServerAddress>() );

        // then
        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "one" ),
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" ),
                new BoltServerAddress( "fyr" )}, set.toArray() );
    }

    @Test
    public void shouldPreserveOrderWhenRemoving() throws Exception
    {
        // given
        Set<BoltServerAddress> servers = addresses( "one", "two", "tre" );
        AddressSet set = new AddressSet();
        set.update( servers, new HashSet<BoltServerAddress>() );

        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "one" ),
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" )}, set.toArray() );

        // when
        set.remove( new BoltServerAddress( "one" ) );

        // then
        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" )}, set.toArray() );
    }

    @Test
    public void shouldPreserveOrderWhenRemovingThroughUpdate() throws Exception
    {
        // given
        Set<BoltServerAddress> servers = addresses( "one", "two", "tre" );
        AddressSet set = new AddressSet();
        set.update( servers, new HashSet<BoltServerAddress>() );

        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "one" ),
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" )}, set.toArray() );

        // when
        servers.remove( new BoltServerAddress( "one" ) );
        set.update( servers, new HashSet<BoltServerAddress>() );

        // then
        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" )}, set.toArray() );
    }

    @Test
    public void shouldRecordRemovedAddressesWhenUpdating() throws Exception
    {
        // given
        AddressSet set = new AddressSet();
        set.update( addresses( "one", "two", "tre" ), new HashSet<BoltServerAddress>() );

        // when
        HashSet<BoltServerAddress> removed = new HashSet<>();
        set.update( addresses( "one", "two", "fyr" ), removed );

        // then
        assertEquals( singleton( new BoltServerAddress( "tre" ) ), removed );
    }

    @Test
    public void shouldExposeEmptyArrayWhenEmpty()
    {
        AddressSet addressSet = new AddressSet();

        BoltServerAddress[] addresses = addressSet.toArray();

        assertEquals( 0, addresses.length );
    }

    @Test
    public void shouldExposeCorrectArray()
    {
        AddressSet addressSet = new AddressSet();
        addressSet.update( addresses( "one", "two", "tre" ), new HashSet<BoltServerAddress>() );

        BoltServerAddress[] addresses = addressSet.toArray();

        assertArrayEquals( new BoltServerAddress[]{
                new BoltServerAddress( "one" ),
                new BoltServerAddress( "two" ),
                new BoltServerAddress( "tre" )}, addresses );
    }

    @Test
    public void shouldHaveSizeZeroWhenEmpty()
    {
        AddressSet addressSet = new AddressSet();

        assertEquals( 0, addressSet.size() );
    }

    @Test
    public void shouldHaveCorrectSize()
    {
        AddressSet addressSet = new AddressSet();
        addressSet.update( addresses( "one", "two" ), new HashSet<BoltServerAddress>() );

        assertEquals( 2, addressSet.size() );
    }

    private static Set<BoltServerAddress> addresses( String... strings )
    {
        Set<BoltServerAddress> set = new LinkedHashSet<>();
        for ( String string : strings )
        {
            set.add( new BoltServerAddress( string ) );
        }
        return set;
    }
}
