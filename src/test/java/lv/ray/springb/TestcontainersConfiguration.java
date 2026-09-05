package lv.ray.springb;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;


/**
 * Import this into any {@code @SpringBootTest} that needs a real Postgres - it replaces the
 * application's normal {@code spring.datasource.*} (the local dev database) with a disposable
 * container for the lifetime of the test JVM. {@code @ServiceConnection} wires the container's
 * JDBC URL/credentials into the datasource automatically, so no manual property overrides are
 * needed. Data written by these tests never touches the local dev database, and nothing here
 * needs cleaning up afterwards - the container is destroyed with it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration
{

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer()
	{
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}
}
