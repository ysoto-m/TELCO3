package com.telco3.agentui.vicidial;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VicidialRuntimeDataSourceFactoryTest {

  @Test
  void buildsMariaDbDatasourceUsingAdminDbSettings() {
    VicidialConfigService configService = mock(VicidialConfigService.class);
    when(configService.resolveDbConfig()).thenReturn(new VicidialConfigService.ResolvedVicidialDbConfig(
        "172.17.248.220", "3306", "asterisk", "api_vicidial", "secret", false, 1L
    ));

    VicidialRuntimeDataSourceFactory factory = new VicidialRuntimeDataSourceFactory(
        configService,
        "org.mariadb.jdbc.Driver",
        60000
    );

    DataSource dataSource = factory.getOrCreate();
    HikariDataSource hikariDataSource = assertInstanceOf(HikariDataSource.class, dataSource);

    assertEquals("org.mariadb.jdbc.Driver", hikariDataSource.getDriverClassName());
    assertEquals("jdbc:mariadb://172.17.248.220:3306/asterisk", hikariDataSource.getJdbcUrl());
    assertEquals("api_vicidial", hikariDataSource.getUsername());
    assertEquals("VicidialRuntimePool", hikariDataSource.getPoolName());

    factory.invalidate();
  }
}
