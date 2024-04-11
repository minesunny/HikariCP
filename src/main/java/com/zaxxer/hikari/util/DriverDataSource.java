/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
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
package com.zaxxer.hikari.util;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DriverDataSource implements DataSource
{
   private static final Logger LOGGER = LoggerFactory.getLogger(DriverDataSource.class);
   private static final String PASSWORD = "password";
   private static final String USER = "user";

   private final String jdbcUrl;
   private final Properties driverProperties;
   private final Driver driver;

   public DriverDataSource(String jdbcUrl, Driver driver, Properties properties, String username, String password)
   {
      this.jdbcUrl = jdbcUrl;
      this.driverProperties = new Properties();
      this.driver = driver;
      for (var entry : properties.entrySet()) {
         driverProperties.setProperty(entry.getKey().toString(), entry.getValue().toString());
      }

      if (username != null) {
         driverProperties.put(USER, driverProperties.getProperty(USER, username));
      }
      if (password != null) {
         driverProperties.put(PASSWORD, driverProperties.getProperty(PASSWORD, password));
      }


      final var sanitizedUrl = jdbcUrl.replaceAll("([?&;][^&#;=]*[pP]assword=)[^&#;]*", "$1<masked>");

      try {
         if (driver == null) {
            driver = DriverManager.getDriver(jdbcUrl);
            LOGGER.debug("Loaded driver with class name {} for jdbcUrl={}", driver.getClass().getName(), sanitizedUrl);
         }
         else if (!driver.acceptsURL(jdbcUrl)) {
            throw new RuntimeException("Driver " + driver.getClass().getName() + " claims to not accept jdbcUrl, " + sanitizedUrl);
         }
      }
      catch (SQLException e) {
         throw new RuntimeException("Failed to get driver instance for jdbcUrl=" + sanitizedUrl, e);
      }
   }
   @Override
   public Connection getConnection() throws SQLException
   {
      return driver.connect(jdbcUrl, driverProperties);
   }

   @Override
   public Connection getConnection(final String username, final String password) throws SQLException
   {
      final var cloned = (Properties) driverProperties.clone();
      if (username != null) {
         cloned.put(USER, username);
         if (cloned.containsKey("username")) {
            cloned.put("username", username);
         }
      }
      if (password != null) {
         cloned.put(PASSWORD, password);
      }

      return driver.connect(jdbcUrl, cloned);
   }

   @Override
   public PrintWriter getLogWriter() throws SQLException
   {
      throw new SQLFeatureNotSupportedException();
   }

   @Override
   public void setLogWriter(PrintWriter logWriter) throws SQLException
   {
      throw new SQLFeatureNotSupportedException();
   }

   @Override
   public void setLoginTimeout(int seconds) throws SQLException
   {
      DriverManager.setLoginTimeout(seconds);
   }

   @Override
   public int getLoginTimeout() throws SQLException
   {
      return DriverManager.getLoginTimeout();
   }

   @Override
   public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
   {
      return driver.getParentLogger();
   }

   @Override
   public <T> T unwrap(Class<T> iface) throws SQLException
   {
      throw new SQLFeatureNotSupportedException();
   }

   @Override
   public boolean isWrapperFor(Class<?> iface) throws SQLException
   {
      return false;
   }
}
