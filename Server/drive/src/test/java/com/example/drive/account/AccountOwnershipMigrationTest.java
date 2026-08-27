package com.example.drive.account;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AccountOwnershipMigrationTest {

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	@Test
	void existingJobsAreAssignedToLegacyAccountAndAccountIdIsRequired() {
		DataSource dataSource = dataSource();
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target("16")
				.load()
				.migrate();

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		UUID jobId = UUID.randomUUID();
		jdbc.update("""
						insert into jobs (id, input_uri, status, priority, created_at, updated_at)
						values (?, 's3://media-input/legacy.mp4', 'QUEUED', 'NORMAL', now(), now())
						""",
				jobId
		);

		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.load()
				.migrate();

		UUID accountId = jdbc.queryForObject(
				"select account_id from jobs where id = ?",
				UUID.class,
				jobId
		);
		assertThat(accountId).isEqualTo(LegacyAccounts.SYSTEM_ACCOUNT_ID);
		assertThat(jdbc.queryForObject(
				"select name from accounts where id = ?",
				String.class,
				LegacyAccounts.SYSTEM_ACCOUNT_ID
		)).isEqualTo(LegacyAccounts.SYSTEM_ACCOUNT_NAME);
		assertThat(jdbc.queryForObject(
				"select count(*) from jobs where account_id is null",
				Integer.class
		)).isZero();

		assertThatThrownBy(() -> jdbc.update("""
				insert into jobs (id, input_uri, status, priority, created_at, updated_at)
				values (?, 's3://media-input/missing-owner.mp4', 'QUEUED', 'NORMAL', now(), now())
				""", UUID.randomUUID()))
				.hasMessageContaining("account_id");
	}

	private static DataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUsername(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());
		return dataSource;
	}
}
