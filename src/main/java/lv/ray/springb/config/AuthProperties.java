package lv.ray.springb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


/**
 * Registration and sign-in policy, bound from {@code springb.auth.*}. The values below are the
 * defaults applied when a property is absent.
 */
@ConfigurationProperties(prefix = "springb.auth")
public record AuthProperties(

		@DefaultValue("3") int minUsernameLength,

		@DefaultValue("8") int minPasswordLength,

		@DefaultValue("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String emailPattern,

		@DefaultValue("ROLE_USER") String defaultRole)
{
}
