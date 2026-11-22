package com.zaxxer.hikari.pool;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.ConnectionStats;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.PoolStats;
import com.zaxxer.hikari.mocks.StubDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.zaxxer.hikari.pool.TestElf.newHikariDataSource;
import static junit.framework.TestCase.fail;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * @author wvuong@chariotsolutions.com on 2/16/17.
 */
public class MetricsTrackerTest
{
   @Test
   public void connectionTimeoutIsRecorded() throws Exception
   {
      assertThrows(SQLTransientConnectionException.class, () -> {
         int timeoutMillis = 1000;
         int timeToCreateNewConnectionMillis = timeoutMillis * 2;

         StubDataSource stubDataSource = new StubDataSource();
         stubDataSource.setConnectionAcquisitionTime(timeToCreateNewConnectionMillis);

         StubMetricsTracker metricsTracker = new StubMetricsTracker();

         try (HikariDataSource ds = newHikariDataSource()) {
            ds.setMinimumIdle(0);
            ds.setMaximumPoolSize(1);
            ds.setConnectionTimeout(timeoutMillis);
            ds.setDataSource(stubDataSource);
            ds.setMetricsTrackerFactory((poolName, poolStats) -> metricsTracker);

            try (Connection c = ds.getConnection()) {
               fail("Connection shouldn't have been successfully created due to configured connection timeout");

            } finally {
               // assert that connection timeout was measured
               assertThat(metricsTracker.connectionTimeoutRecorded, is(true));
               assertArrayEquals(new long[] { 0, 0, 0 }, metricsTracker.acquiredBorrow);
               // assert that measured time to acquire connection should be roughly equal or greater than the configured connection timeout time
               assertTrue(metricsTracker.connectionAcquiredNanos >= TimeUnit.NANOSECONDS.convert(timeoutMillis, TimeUnit.MILLISECONDS));

               metricsTracker.close();
            }
         }
      });
   }

   @Test
   public void failedCreationHasNoConnectionOrBorrowId() throws Exception
   {
      StubMetricsTracker metricsTracker = new StubMetricsTracker();
      StubDataSource stubDataSource = new StubDataSource();
      stubDataSource.setThrowException(new SQLException("Connection unavailable"));

      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setInitializationFailTimeout(-1);
         ds.setConnectionTimeout(500);
         ds.setDataSource(stubDataSource);
         ds.setMetricsTrackerFactory((poolName, poolStats) -> metricsTracker);

         assertThrows(SQLTransientConnectionException.class, ds::getConnection);
         assertTrue(metricsTracker.creationRecorded.await(5, TimeUnit.SECONDS));
         assertArrayEquals(new long[] { 0, 0 }, metricsTracker.createdConnection);
      }
   }

   @Test
   public void borrowAndReturnReportTheSameIdentifiers() throws Exception
   {
      StubMetricsTracker metricsTracker = new StubMetricsTracker();

      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setInitializationFailTimeout(-1);
         ds.setDataSource(new StubDataSource());
         ds.setMetricsTrackerFactory((poolName, poolStats) -> metricsTracker);

         long[] firstBorrow;
         try (ProxyConnection first = (ProxyConnection) ds.getConnection()) {
            firstBorrow = new long[] { first.getId(), first.getBorrowId(), 1 };
            assertArrayEquals(new long[] { first.getId(), 0 }, metricsTracker.createdConnection);
            assertArrayEquals(firstBorrow, metricsTracker.acquiredBorrow);
            assertNull(metricsTracker.returnedBorrow);
         }
         assertArrayEquals(firstBorrow, metricsTracker.returnedBorrow);

         long[] secondBorrow;
         try (ProxyConnection second = (ProxyConnection) ds.getConnection()) {
            secondBorrow = new long[] { firstBorrow[0], second.getBorrowId(), 2 };
            assertNotEquals(firstBorrow[1], secondBorrow[1]);
            assertArrayEquals(secondBorrow, metricsTracker.acquiredBorrow);
            assertArrayEquals(firstBorrow, metricsTracker.returnedBorrow);
         }
         assertArrayEquals(secondBorrow, metricsTracker.returnedBorrow);
      }
   }

   @Test
   public void poolStatsCountsExecutionsByBorrowId() throws SQLException
   {
      StubMetricsTracker tracker = new StubMetricsTracker();
      JdbcDataSource source = new JdbcDataSource();
      source.setURL("jdbc:h2:mem:connection_execution_stats");

      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setDataSource(source);
         ds.setMetricsTrackerFactory((poolName, poolStats) -> {
            tracker.poolStats = poolStats;
            return tracker;
         });

         long connectionId;
         long firstBorrowId;
         Statement previousStatement;
         try (ProxyConnection connection = (ProxyConnection) ds.getConnection();
              Statement statement = connection.createStatement()) {
            previousStatement = statement;
            connectionId = connection.getId();
            firstBorrowId = connection.getBorrowId();
            assertTrue(tracker.poolStats.getConnectionStats(connectionId).isEmpty());
            assertEquals(0L, tracker.poolStats.getConnectionStats(firstBorrowId).orElseThrow().getExecutionCount());

            statement.execute("CREATE TABLE items (id INT PRIMARY KEY)");
            try (var insert = connection.prepareStatement("INSERT INTO items VALUES (?)")) {
               insert.setInt(1, 1);
               assertEquals(1L, insert.executeLargeUpdate());
               assertThrows(SQLException.class, insert::executeUpdate);
               insert.setInt(1, 2);
               insert.addBatch();
               assertArrayEquals(new long[] { 1 }, insert.executeLargeBatch());
            }

            try (var call = connection.prepareCall("CALL ABS(-5)");
                 var result = call.executeQuery()) {
               assertTrue(result.next());
               assertEquals(5, result.getInt(1));
            }

            var stats = tracker.poolStats.getConnectionStats(firstBorrowId).orElseThrow();
            assertEquals(5L, stats.getExecutionCount());
            assertEquals(1L, stats.getFailureCount());
            assertEquals(0, stats.getRunningCount());
            assertEquals(0L, stats.getOldestExecutionStartedAtEpochMillis());
         }
         assertEquals(5L, tracker.returnedStats.getExecutionCount());
         assertEquals(1L, tracker.returnedStats.getFailureCount());
         assertEquals(5L, tracker.poolStats.getExecutionCount());
         assertEquals(1L, tracker.poolStats.getFailureCount());
         assertTrue(tracker.poolStats.getConnectionStats(firstBorrowId).isEmpty());

         long secondBorrowId;
         try (ProxyConnection connection = (ProxyConnection) ds.getConnection();
              Statement statement = connection.createStatement()) {
            secondBorrowId = connection.getBorrowId();
            assertEquals(connectionId, connection.getId());
            assertNotEquals(firstBorrowId, secondBorrowId);

            assertThrows(SQLException.class, () -> previousStatement.execute("SELECT 1"));
            var freshStats = tracker.poolStats.getConnectionStats(secondBorrowId).orElseThrow();
            assertEquals(0L, freshStats.getExecutionCount());
            assertEquals(0L, freshStats.getFailureCount());

            try (var result = statement.executeQuery("SELECT COUNT(*) FROM items")) {
               assertTrue(result.next());
               assertEquals(2, result.getInt(1));
            }
            var stats = tracker.poolStats.getConnectionStats(secondBorrowId).orElseThrow();
            assertEquals(1L, stats.getExecutionCount());
            assertEquals(0L, stats.getFailureCount());
         }

         assertTrue(tracker.poolStats.getConnectionStats(firstBorrowId).isEmpty());
         assertTrue(tracker.poolStats.getConnectionStats(secondBorrowId).isEmpty());
         assertEquals(6L, tracker.poolStats.getExecutionCount());
         assertEquals(1L, tracker.poolStats.getFailureCount());
      }
   }

   @Test
   public void poolStatsListsActiveBorrows() throws SQLException
   {
      StubMetricsTracker tracker = new StubMetricsTracker();

      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(2);
         ds.setDataSource(new StubDataSource());
         ds.setMetricsTrackerFactory((poolName, poolStats) -> {
            tracker.poolStats = poolStats;
            return tracker;
         });

         try (ProxyConnection first = (ProxyConnection) ds.getConnection();
              ProxyConnection second = (ProxyConnection) ds.getConnection()) {
            final var snapshot = tracker.poolStats.getConnectionStats();
            assertEquals(2, snapshot.size());
            assertTrue(snapshot.stream().anyMatch(stats -> stats.getBorrowId() == first.getBorrowId()));
            assertTrue(snapshot.stream().anyMatch(stats -> stats.getBorrowId() == second.getBorrowId()));

            first.close();

            final var remaining = tracker.poolStats.getConnectionStats();
            assertEquals(1, remaining.size());
            assertEquals(second.getBorrowId(), remaining.get(0).getBorrowId());
            assertEquals(2, snapshot.size());
         }

         assertTrue(tracker.poolStats.getConnectionStats().isEmpty());
      }
   }

   @Test(timeout = 20_000)
   public void poolStatsRemainConsistentDuringConcurrentBorrowAndExecution() throws Exception
   {
      final int workers = 4;
      final int rounds = 48;
      final var source = new JdbcDataSource();
      source.setURL("jdbc:h2:mem:concurrent_execution_counts");
      final var statsRef = new AtomicReference<PoolStats>();
      final var connected = new CountDownLatch(workers);
      final var start = new CountDownLatch(1);
      final var finished = new CountDownLatch(workers);
      final var executor = Executors.newFixedThreadPool(workers, task -> {
         final var thread = new Thread(task, "hikari-metrics-test");
         thread.setDaemon(true);
         return thread;
      });

      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(workers);
         ds.setDataSource(source);
         ds.setMetricsTrackerFactory((poolName, stats) -> {
            statsRef.set(stats);
            return new IMetricsTracker() {};
         });

         final var operations = new ArrayList<Future<Void>>();
         for (int worker = 0; worker < workers; worker++) {
            operations.add(executor.submit(() -> {
               try {
                  for (int round = 0; round < rounds; round++) {
                     try (var connection = ds.getConnection();
                          var statement = connection.createStatement()) {
                        if (round == 0) {
                           connected.countDown();
                           assertTrue(start.await(5, TimeUnit.SECONDS));
                        }
                        try (var rows = statement.executeQuery("SELECT 1")) {
                           assertTrue(rows.next());
                           assertEquals(1, rows.getInt(1));
                        }
                        assertEquals("22012", assertThrows(SQLException.class,
                           () -> statement.executeQuery("SELECT 1 / 0")).getSQLState());
                     }
                  }
                  return null;
               }
               finally {
                  finished.countDown();
               }
            }));
         }

         try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            final var stats = statsRef.get();
            assertEquals(workers, stats.getConnectionStats().size());
            start.countDown();
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            long previousExecutions = 0;
            long previousFailures = 0;
            do {
               final long executions = stats.getExecutionCount();
               final long failures = stats.getFailureCount();
               assertTrue(executions >= previousExecutions);
               assertTrue(failures >= previousFailures);
               previousExecutions = executions;
               previousFailures = failures;
               for (var borrow : stats.getConnectionStats()) {
                  assertTrue(borrow.getExecutionCount() <= 2);
                  assertTrue(borrow.getFailureCount() <= 1);
                  assertTrue(borrow.getFailureCount() <= borrow.getExecutionCount());
               }
            }
            while (!finished.await(5, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline);

            assertEquals("Deadlocked JVM threads: "
               + Arrays.toString(ManagementFactory.getThreadMXBean().findDeadlockedThreads()),
               0, finished.getCount());
            for (var operation : operations) {
               operation.get(1, TimeUnit.SECONDS);
            }
            assertEquals((long) workers * rounds * 2, stats.getExecutionCount());
            assertEquals((long) workers * rounds, stats.getFailureCount());
            assertTrue(stats.getConnectionStats().isEmpty());
            assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections());
         }
         finally {
            start.countDown();
         }
      }
      finally {
         executor.shutdownNow();
      }
   }

   @Test
   public void poolStatsTracksOverlappingExecutions() throws Exception
   {
      final var tracker = new StubMetricsTracker();
      final var executor = Executors.newFixedThreadPool(2);
      final var firstStarted = new CountDownLatch(1);
      final var secondStarted = new CountDownLatch(1);
      final var finishFirst = new CountDownLatch(1);
      final var finishSecond = new CountDownLatch(1);

      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setDataSource(new StubDataSource());
         ds.setMetricsTrackerFactory((poolName, poolStats) -> {
            tracker.poolStats = poolStats;
            return tracker;
         });

         try (var connection = (ProxyConnection) ds.getConnection();
              var first = blockingStatement(connection, firstStarted, finishFirst);
              var second = blockingStatement(connection, secondStarted, finishSecond)) {
            final var borrowId = connection.getBorrowId();
            final var initial = tracker.poolStats.getConnectionStats(borrowId).orElseThrow();
            assertTrue(initial.getBorrowedAtEpochMillis() > 0);
            assertEquals(initial.getBorrowedAtEpochMillis(), initial.getLastActivityAtEpochMillis());
            assertEquals(0, initial.getRunningCount());

            final var firstExecution = executor.submit(() -> first.execute("SELECT 1"));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            final var firstRunning = tracker.poolStats.getConnectionStats(borrowId).orElseThrow();
            assertEquals(1, firstRunning.getRunningCount());
            assertEquals(firstRunning.getLastActivityAtEpochMillis(), firstRunning.getOldestExecutionStartedAtEpochMillis());

            final var secondExecution = executor.submit(() -> second.execute("SELECT 2"));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            final var bothRunning = tracker.poolStats.getConnectionStats(borrowId).orElseThrow();
            assertEquals(2, bothRunning.getRunningCount());
            assertEquals(firstRunning.getOldestExecutionStartedAtEpochMillis(), bothRunning.getOldestExecutionStartedAtEpochMillis());

            finishFirst.countDown();
            firstExecution.get(5, TimeUnit.SECONDS);
            final var secondRunning = tracker.poolStats.getConnectionStats(borrowId).orElseThrow();
            assertEquals(1, secondRunning.getRunningCount());
            assertEquals(bothRunning.getLastActivityAtEpochMillis(), secondRunning.getOldestExecutionStartedAtEpochMillis());

            finishSecond.countDown();
            secondExecution.get(5, TimeUnit.SECONDS);
            final var idle = tracker.poolStats.getConnectionStats(borrowId).orElseThrow();
            assertEquals(initial.getBorrowedAtEpochMillis(), idle.getBorrowedAtEpochMillis());
            assertEquals(2L, idle.getExecutionCount());
            assertEquals(0L, idle.getFailureCount());
            assertEquals(0, idle.getRunningCount());
            assertEquals(0L, idle.getOldestExecutionStartedAtEpochMillis());
            assertTrue(idle.getLastActivityAtEpochMillis() >= bothRunning.getLastActivityAtEpochMillis());
         }
      }
      finally {
         finishFirst.countDown();
         finishSecond.countDown();
         executor.shutdownNow();
      }
   }

   private static Statement blockingStatement(ProxyConnection connection, CountDownLatch started, CountDownLatch finish)
   {
      final var statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
         new Class<?>[] { Statement.class }, (proxy, method, arguments) -> {
            if (!"execute".equals(method.getName())) {
               return null;
            }

            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) {
               throw new SQLException("Execution was not released");
            }
            return false;
         });
      return ProxyFactory.getProxyStatement(connection, statement);
   }

   private static class StubMetricsTracker implements IMetricsTracker
   {
      private PoolStats poolStats;
      private Long connectionAcquiredNanos;
      private boolean connectionTimeoutRecorded;
      private final CountDownLatch creationRecorded = new CountDownLatch(1);
      private long[] createdConnection;
      private long[] acquiredBorrow;
      private long[] returnedBorrow;
      private ConnectionStats returnedStats;

      @Override
      public void recordConnectionCreatedMillis(long connectionCreatedMillis, long connectionId, long borrowId)
      {
         createdConnection = new long[] { connectionId, borrowId };
         creationRecorded.countDown();
      }

      @Override
      public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos, long connectionId, long borrowId, long borrowCount)
      {
         connectionAcquiredNanos = elapsedAcquiredNanos;
         acquiredBorrow = new long[] { connectionId, borrowId, borrowCount };
      }

      @Override
      public void recordConnectionUsageMillis(long elapsedBorrowedMillis, long connectionId, long borrowId, long borrowCount)
      {
         returnedBorrow = new long[] { connectionId, borrowId, borrowCount };
         if (poolStats != null) {
            returnedStats = poolStats.getConnectionStats(borrowId).orElseThrow();
         }
      }

      @Override
      public void recordConnectionTimeout()
      {
         this.connectionTimeoutRecorded = true;
      }
   }
}
