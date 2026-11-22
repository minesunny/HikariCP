/*
 * Copyright (C) 2015 Brett Wooldridge
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

/** A snapshot of JDBC executions during one borrow. */
public final class ConnectionStats
{
   private final long borrowId;
   private final long executionCount;
   private final long failureCount;
   private final long borrowedAtEpochMillis;
   private final long lastActivityAtEpochMillis;
   private final int runningCount;
   private final long oldestExecutionStartedAtEpochMillis;

   public ConnectionStats(long borrowId, long executionCount, long failureCount, long borrowedAtEpochMillis,
                          long lastActivityAtEpochMillis, int runningCount, long oldestExecutionStartedAtEpochMillis)
   {
      this.borrowId = borrowId;
      this.executionCount = executionCount;
      this.failureCount = failureCount;
      this.borrowedAtEpochMillis = borrowedAtEpochMillis;
      this.lastActivityAtEpochMillis = lastActivityAtEpochMillis;
      this.runningCount = runningCount;
      this.oldestExecutionStartedAtEpochMillis = oldestExecutionStartedAtEpochMillis;
   }

   /** @return the identifier reported by the acquisition metrics callback */
   public long getBorrowId()
   {
      return borrowId;
   }

   /** @return the number of JDBC execution calls started */
   public long getExecutionCount()
   {
      return executionCount;
   }

   /** @return the number of JDBC execution calls that failed */
   public long getFailureCount()
   {
      return failureCount;
   }

   /** @return the borrow start time in milliseconds since the Unix epoch */
   public long getBorrowedAtEpochMillis()
   {
      return borrowedAtEpochMillis;
   }

   /** @return the latest execution start or finish time, or the borrow start time before any execution */
   public long getLastActivityAtEpochMillis()
   {
      return lastActivityAtEpochMillis;
   }

   /** @return the number of executions currently in progress */
   public int getRunningCount()
   {
      return runningCount;
   }

   /** @return the start time of the oldest running execution in milliseconds since the Unix epoch, or 0 when idle */
   public long getOldestExecutionStartedAtEpochMillis()
   {
      return oldestExecutionStartedAtEpochMillis;
   }
}
