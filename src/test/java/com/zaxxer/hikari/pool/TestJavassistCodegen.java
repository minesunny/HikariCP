package com.zaxxer.hikari.pool;

import com.zaxxer.hikari.mocks.StubDataSource;
import com.zaxxer.hikari.pool.TestElf.FauxWebClassLoader;
import com.zaxxer.hikari.util.JavassistProxyFactory;
import org.junit.Assert;
import org.junit.Test;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;

public class TestJavassistCodegen {
   @Test
   public void testCodegen() throws Exception {
      String tmp = System.getProperty("java.io.tmpdir");
      JavassistProxyFactory.main(tmp);

      Path base = Paths.get(tmp, "target/classes/com/zaxxer/hikari/pool".split("/"));
      Assert.assertTrue("", Files.isRegularFile(base.resolve("HikariProxyConnection.class")));
      Assert.assertTrue("", Files.isRegularFile(base.resolve("HikariProxyStatement.class")));
      Assert.assertTrue("", Files.isRegularFile(base.resolve("HikariProxyCallableStatement.class")));
      Assert.assertTrue("", Files.isRegularFile(base.resolve("HikariProxyPreparedStatement.class")));
      Assert.assertTrue("", Files.isRegularFile(base.resolve("HikariProxyResultSet.class")));
      Assert.assertTrue("", Files.isRegularFile(base.resolve("ProxyFactory.class")));

      FauxWebClassLoader fauxClassLoader = new FauxWebClassLoader();

      int mod = fauxClassLoader.loadClass("com.zaxxer.hikari.pool.HikariProxyConnection").getModifiers();
      Assert.assertTrue("Generated proxy class should be public", Modifier.isPublic(mod));
      Assert.assertTrue("Generated proxy class should be final", Modifier.isFinal(mod));

      Class<?> configClass = fauxClassLoader.loadClass("com.zaxxer.hikari.HikariConfig");
      Object config = configClass.getConstructor().newInstance();
      configClass.getMethod("setDataSource", DataSource.class).invoke(config, new StubDataSource());
      configClass.getMethod("setMaximumPoolSize", int.class).invoke(config, 1);

      Class<?> dataSourceClass = fauxClassLoader.loadClass("com.zaxxer.hikari.HikariDataSource");
      try (AutoCloseable dataSource = (AutoCloseable) dataSourceClass.getConstructor(configClass).newInstance(config);
           Connection connection = ((DataSource) dataSource).getConnection();
           Statement statement = connection.createStatement()) {
         Assert.assertSame(fauxClassLoader, connection.getClass().getClassLoader());
         Assert.assertSame(fauxClassLoader, statement.getClass().getClassLoader());
      }
   }
}
