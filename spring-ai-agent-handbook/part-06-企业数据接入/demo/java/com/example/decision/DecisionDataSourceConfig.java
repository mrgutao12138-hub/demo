package com.example.decision;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 决策库专用数据源：只读账号，与生产写库物理隔离。
 */
@Configuration
public class DecisionDataSourceConfig {

    @Bean
    public DataSource decisionDataSource(
            @Value("${decision.datasource.url}") String url,
            @Value("${decision.datasource.username}") String username,
            @Value("${decision.datasource.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(true);
        config.setMaximumPoolSize(5);
        config.setPoolName("decision-readonly-pool");
        return new HikariDataSource(config);
    }

    @Bean
    public NamedParameterJdbcTemplate decisionJdbcTemplate(DataSource decisionDataSource) {
        return new NamedParameterJdbcTemplate(decisionDataSource);
    }
}
