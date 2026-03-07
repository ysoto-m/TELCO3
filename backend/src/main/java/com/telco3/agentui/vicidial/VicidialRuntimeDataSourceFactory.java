package com.telco3.agentui.vicidial;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Objects;

@Component
public class VicidialRuntimeDataSourceFactory {
  private static final Logger log = LoggerFactory.getLogger(VicidialRuntimeDataSourceFactory.class);

  private final VicidialConfigService configService;
  private final String defaultDriverClassName;
  private final long ttlMillis;

  private volatile CachedDataSource cached;

  public VicidialRuntimeDataSourceFactory(
      VicidialConfigService configService,
      @Value("${vicidial.datasource.driver-class-name:org.mariadb.jdbc.Driver}") String defaultDriverClassName,
      @Value("${vicidial.datasource.ttl-ms:60000}") long ttlMillis
  ) {
    this.configService = configService;
    this.defaultDriverClassName = defaultDriverClassName;
    this.ttlMillis = ttlMillis;
  }

  public DataSource getOrCreate() {
    var cfg = configService.resolveDbConfig();
    if (cfg.missingRequired()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT, "VICIDIAL_SETTINGS_MISSING", "Falta configuración MariaDB de Vicidial.", "configure en Admin > Settings", null);
    }

    long now = System.currentTimeMillis();
    CachedDataSource current = cached;
    if (current != null && current.configVersion == cfg.configVersion() && now < current.expiresAt) {
      return current.dataSource;
    }

    synchronized (this) {
      current = cached;
      if (current != null && current.configVersion == cfg.configVersion() && now < current.expiresAt) {
        return current.dataSource;
      }

      HikariDataSource next = build(cfg);
      cached = new CachedDataSource(next, cfg.configVersion(), now + ttlMillis);
      if (current != null) {
        current.dataSource.close();
      }
      return next;
    }
  }

  private HikariDataSource build(VicidialConfigService.ResolvedVicidialDbConfig cfg) {
    String jdbcUrl = buildJdbcUrl(cfg);
    log.info(
        "Building Vicidial datasource host={} port={} database={} driver={} jdbcUrl={}",
        cfg.dbHost(),
        cfg.dbPort(),
        cfg.dbName(),
        defaultDriverClassName,
        jdbcUrl
    );

    HikariConfig hk = new HikariConfig();
    hk.setDriverClassName(defaultDriverClassName);
    hk.setJdbcUrl(jdbcUrl);
    hk.setUsername(cfg.dbUser());
    hk.setPassword(cfg.dbPass());
    hk.setMaximumPoolSize(3);
    hk.setMinimumIdle(0);
    hk.setPoolName("VicidialRuntimePool");
    hk.setInitializationFailTimeout(2000);
    hk.setConnectionTimeout(3000);
    hk.setIdleTimeout(60000);
    hk.setMaxLifetime(180000);
    return new HikariDataSource(hk);
  }

  private String buildJdbcUrl(VicidialConfigService.ResolvedVicidialDbConfig cfg) {
    return "jdbc:mariadb://" + cfg.dbHost() + ":" + cfg.dbPort() + "/" + cfg.dbName();
  }

  public void invalidate() {
    CachedDataSource current = cached;
    cached = null;
    if (current != null) {
      current.dataSource.close();
    }
  }

  private static class CachedDataSource {
    private final HikariDataSource dataSource;
    private final long configVersion;
    private final long expiresAt;

    private CachedDataSource(HikariDataSource dataSource, long configVersion, long expiresAt) {
      this.dataSource = Objects.requireNonNull(dataSource);
      this.configVersion = configVersion;
      this.expiresAt = expiresAt;
    }
  }
}
