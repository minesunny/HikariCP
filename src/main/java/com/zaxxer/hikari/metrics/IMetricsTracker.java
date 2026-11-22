/*
 * Copyright (C) 2017 Brett Wooldridge
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

package com.zaxxer.hikari.metrics;

/**
 * @author Brett Wooldridge
 */
public interface IMetricsTracker extends AutoCloseable
{
   /**
    * Records a connection creation attempt after metrics are enabled, including JDBC setup.
    *
    * @param connectionCreatedMillis elapsed creation and setup time in milliseconds
    * @param connectionId identifier assigned to the connection, or 0 if creation failed
    * @param borrowId 0 for creation events, which precede checkout
    */
   default void recordConnectionCreatedMillis(long connectionCreatedMillis, long connectionId, long borrowId) {}

   /**
    * Records a successful acquisition or an acquisition timeout.
    *
    * @param elapsedAcquiredNanos elapsed acquisition time in nanoseconds
    * @param connectionId identifier of the acquired connection, or 0 on timeout
    * @param borrowId identifier of this borrow, or 0 on timeout
    * @param borrowCount one-based borrow number for the connection, or 0 on timeout
    */
   default void recordConnectionAcquiredNanos(long elapsedAcquiredNanos, long connectionId, long borrowId, long borrowCount) {}

   /**
    * Records the duration of a borrow returned to the pool.
    *
    * @param elapsedBorrowedMillis elapsed time of the borrow in milliseconds
    * @param connectionId identifier of the returned connection
    * @param borrowId identifier of the completed borrow
    * @param borrowCount one-based borrow number for the connection
    */
   default void recordConnectionUsageMillis(long elapsedBorrowedMillis, long connectionId, long borrowId, long borrowCount) {}

   default void recordConnectionTimeout() {}

   @Override
   default void close() {}
}
