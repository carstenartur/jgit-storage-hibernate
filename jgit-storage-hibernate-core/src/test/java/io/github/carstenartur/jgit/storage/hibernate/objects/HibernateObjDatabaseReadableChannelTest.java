/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.junit.jupiter.api.Test;

class HibernateObjDatabaseReadableChannelTest {

  @Test
  void baseInlineChannelSupportsTheCompleteReadableChannelContract() throws Exception {
    byte[] data = {3, 5, 8, 13};
    ReadableChannel channel = inlineChannel(data);

    assertTrue(channel.isOpen());
    assertEquals(0L, channel.position());
    assertEquals(data.length, channel.size());
    assertEquals(0, channel.blockSize());
    channel.setReadAheadBytes(Integer.MAX_VALUE);

    ByteBuffer first = ByteBuffer.allocate(2);
    assertEquals(2, channel.read(first));
    assertArrayEquals(new byte[] {3, 5}, first.array());
    assertEquals(2L, channel.position());

    channel.position(1);
    ByteBuffer remainder = ByteBuffer.allocate(3);
    assertEquals(3, channel.read(remainder));
    assertArrayEquals(new byte[] {5, 8, 13}, remainder.array());
    assertEquals(-1, channel.read(ByteBuffer.allocate(1)));

    channel.close();
    assertFalse(channel.isOpen());
  }

  private static ReadableChannel inlineChannel(byte[] data) throws Exception {
    Class<?> type =
        Class.forName(
            "io.github.carstenartur.jgit.storage.hibernate.objects."
                + "HibernateObjDatabase$ByteArrayReadableChannel");
    Constructor<?> constructor = type.getDeclaredConstructor(byte[].class);
    constructor.setAccessible(true);
    return (ReadableChannel) constructor.newInstance((Object) data);
  }
}
