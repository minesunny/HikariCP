/*
 * Copyright (C) 2014 Brett Wooldridge
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
package com.zaxxer.hikari.pool;

import com.zaxxer.hikari.metrics.ConnectionStats;
import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import com.zaxxer.hikari.util.FastList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static com.zaxxer.hikari.util.ClockSource.*;
import static com.zaxxer.hikari.util.ClockSource.currentTime;

/**
 * Entry used in the ConcurrentBag to track Connection instances.
 *
 * @author Brett Wooldridge
 */
final class PoolEntry implements IConcurrentBagEntry
{
   private static final Logger LOGGER = LoggerFactory.getLogger(PoolEntry.class);
   private static final AtomicIntegerFieldUpdater<PoolEntry> stateUpdater;
   private static final AtomicLong idSequence = new AtomicLong();

   final long id = nextId();
   Connection connection;
   long lastAccessed;
   long lastBorrowed;

   private long borrowCount;
   private long activeBorrowId;
   private long executionCount;
   private long failureCount;
   private long borrowedAtEpochMillis;
   private long lastActivityAtEpochMillis;
   private final Map<Long, Long> runningExecutions = new LinkedHashMap<>();

   @SuppressWarnings("FieldCanBeLocal")
   private volatile int state = 0;
   private volatile boolean evict;

   private volatile ScheduledFuture<?> endOfLife;
   private volatile ScheduledFuture<?> keepalive;

   private final FastList<Statement> openStatements;
   private final HikariPool hikariPool;

   private final boolean isReadOnly;
   private final boolean isAutoCommit;

   static
   {
      stateUpdater = AtomicIntegerFieldUpdater.newUpdater(PoolEntry.class, "state");
   }

   PoolEntry(final Connection connection, final PoolBase pool, final boolean isReadOnly, final boolean isAutoCommit)
   {
      this.connection = connection;
      this.hikariPool = (HikariPool) pool;
      this.isReadOnly = isReadOnly;
      this.isAutoCommit = isAutoCommit;
      this.lastAccessed = currentTime();
      this.openStatements = new FastList<>(Statement.class, 16);
   }

   static long nextId()
   {
      return idSequence.incrementAndGet();
   }

   synchronized long beginBorrow(long borrowId)
   {
      activeBorrowId = borrowId;
      executionCount = 0;
      failureCount = 0;
      borrowedAtEpochMillis = System.currentTimeMillis();
      lastActivityAtEpochMillis = borrowedAtEpochMillis;
      runningExecutions.clear();
      return ++borrowCount;
   }

   synchronized void endBorrow(long borrowId)
   {
      if (activeBorrowId == borrowId) {
         activeBorrowId = 0;
         runningExecutions.clear();
      }
   }

   synchronized long recordExecution(long borrowId)
   {
      if (activeBorrowId != borrowId) {
         return 0;
      }

      lastActivityAtEpochMillis = System.currentTimeMillis();
      hikariPool.recordExecution();
      runningExecutions.put(++executionCount, lastActivityAtEpochMillis);
      return executionCount;
   }

   synchronized void finishExecution(long borrowId, long executionId, boolean failed)
   {
      if (executionId != 0 && failed) {
         hikariPool.recordExecutionFailure();
      }
      if (activeBorrowId == borrowId && runningExecutions.remove(executionId) != null) {
         lastActivityAtEpochMillis = System.currentTimeMillis();
         if (failed) {
            ++failureCount;
         }
      }
   }

   synchronized ConnectionStats getConnectionStats(long borrowId)
   {
      if (borrowId == 0 || activeBorrowId != borrowId) {
         return null;
      }

      final long oldest = runningExecutions.isEmpty() ? 0 : runningExecutions.values().iterator().next();
      return new ConnectionStats(borrowId, executionCount, failureCount, borrowedAtEpochMillis,
         lastActivityAtEpochMillis, runningExecutions.size(), oldest);
   }

   synchronized ConnectionStats getConnectionStats()
   {
      return getConnectionStats(activeBorrowId);
   }

   /**
    * Release this entry back to the pool.
    */
   void recycle(final ProxyConnection proxyConnection)
   {
      if (connection != null) {
         this.lastAccessed = currentTime();
         hikariPool.recycle(proxyConnection);
      }
   }

   /**
    * Set the end of life {@link ScheduledFuture}.
    *
    * @param endOfLife this PoolEntry/Connection's end of life {@link ScheduledFuture}
    */
   void setFutureEol(final ScheduledFuture<?> endOfLife)
   {
      this.endOfLife = endOfLife;
   }

   public void setKeepalive(ScheduledFuture<?> keepalive) {
      this.keepalive = keepalive;
   }

   ProxyConnection createProxyConnection(final ProxyLeakTask leakTask)
   {
      return ProxyFactory.getProxyConnection(this, connection, openStatements, leakTask, isReadOnly, isAutoCommit);
   }

   void resetConnectionState(final ProxyConnection proxyConnection, final int dirtyBits) throws SQLException
   {
      hikariPool.resetConnectionState(connection, proxyConnection, dirtyBits);
   }

   String getPoolName()
   {
      return hikariPool.toString();
   }

   boolean isMarkedEvicted()
   {
      return evict;
   }

   void markEvicted()
   {
      this.evict = true;
   }

   void evict(final String closureReason)
   {
      hikariPool.closeConnection(this, closureReason);
   }

   /** Returns millis since lastBorrowed */
   long getMillisSinceBorrowed()
   {
      return elapsedMillis(lastBorrowed);
   }

   PoolBase getPoolBase()
   {
      return hikariPool;
   }

   /** {@inheritDoc} */
   @Override
   public String toString()
   {
      final var now = currentTime();
      return connection
         + ", accessed " + elapsedDisplayString(lastAccessed, now) + " ago, "
         + stateToString();
   }

   // ***********************************************************************
   //                      IConcurrentBagEntry methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public int getState()
   {
      return stateUpdater.get(this);
   }

   /** {@inheritDoc} */
   @Override
   public boolean compareAndSet(int expect, int update)
   {
      return stateUpdater.compareAndSet(this, expect, update);
   }

   /** {@inheritDoc} */
   @Override
   public void setState(int update)
   {
      stateUpdater.set(this, update);
   }

   Connection close()
   {
      var eol = endOfLife;
      if (eol != null && !eol.isDone() && !eol.cancel(false)) {
         LOGGER.warn("{} - maxLifeTime expiration task cancellation unexpectedly returned false for connection {}", getPoolName(), connection);
      }

      var ka = keepalive;
      if (ka != null && !ka.isDone() && !ka.cancel(false)) {
         LOGGER.warn("{} - keepalive task cancellation unexpectedly returned false for connection {}", getPoolName(), connection);
      }

      var con = connection;
      connection = null;
      endOfLife = null;
      keepalive = null;
      return con;
   }

   private String stateToString()
   {
      switch (state) {
      case STATE_IN_USE:
         return "IN_USE";
      case STATE_NOT_IN_USE:
         return "NOT_IN_USE";
      case STATE_REMOVED:
         return "REMOVED";
      case STATE_RESERVED:
         return "RESERVED";
      default:
         return "Invalid";
      }
   }
}
