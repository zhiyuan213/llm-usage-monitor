package com.metertrail;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Separate physical connections, same authoritative PostgreSQL database (no replica lag). */
@Configuration
class DatabasePools {
  @Bean(name = {"dataSource", "writeDataSource"})
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource writes(DataSourceProperties properties) {
    var pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    pool.setPoolName("metertrail-write");
    pool.setMaximumPoolSize(8);
    pool.setMinimumIdle(1);
    return pool;
  }

  @Bean(name = "readDataSource")
  HikariDataSource reads(
      DataSourceProperties properties,
      @Value("${metertrail.database.read-pool-size:8}") int size,
      @Value("${metertrail.database.read-connection-timeout-ms:1000}") long timeout) {
    var pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    pool.setPoolName("metertrail-read");
    pool.setMaximumPoolSize(size);
    pool.setMinimumIdle(1);
    pool.setConnectionTimeout(timeout);
    pool.setReadOnly(true);
    pool.setConnectionInitSql("SET default_transaction_read_only=on");
    return pool;
  }

  @Bean(name = "jdbcTemplate")
  @Primary
  JdbcTemplate writeJdbc(@Qualifier("writeDataSource") DataSource source) {
    return new JdbcTemplate(source);
  }

  @Bean(name = "readJdbcTemplate")
  JdbcTemplate readJdbc(@Qualifier("readDataSource") DataSource source) {
    return new JdbcTemplate(source);
  }

  @Bean(name = "transactionManager")
  @Primary
  DataSourceTransactionManager writesTx(@Qualifier("writeDataSource") DataSource source) {
    return new DataSourceTransactionManager(source);
  }

  @Bean(name = "readTransactionManager")
  DataSourceTransactionManager readsTx(@Qualifier("readDataSource") DataSource source) {
    return new DataSourceTransactionManager(source);
  }
}
