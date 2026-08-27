package com.example.drive.support;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration
public class InternalAuthTestConfig {

	@Bean
	FilterRegistrationBean<InternalAuthTestInjectorFilter> internalAuthTestInjectorFilter(JdbcTemplate jdbcTemplate) {
		FilterRegistrationBean<InternalAuthTestInjectorFilter> registration =
				new FilterRegistrationBean<>(new InternalAuthTestInjectorFilter(jdbcTemplate));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		registration.addUrlPatterns("/*");
		return registration;
	}
}
