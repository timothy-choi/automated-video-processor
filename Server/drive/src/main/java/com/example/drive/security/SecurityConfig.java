package com.example.drive.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain internalSecurityFilterChain(
			HttpSecurity http,
			InternalServiceAuthenticationFilter internalServiceAuthenticationFilter,
			JsonAuthenticationEntryPoint authenticationEntryPoint,
			JsonAccessDeniedHandler accessDeniedHandler
	) throws Exception {
		http
				.securityMatcher("/internal/**")
				.csrf(csrf -> csrf.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/internal/scheduler/**").hasRole("SCHEDULER")
						.requestMatchers("/internal/workers/**", "/internal/operations/**").hasRole("WORKER")
						.anyRequest().authenticated()
				)
				.addFilterBefore(internalServiceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain applicationSecurityFilterChain(
			HttpSecurity http,
			ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
			JsonAuthenticationEntryPoint authenticationEntryPoint
	) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, "/health").permitAll()
						.requestMatchers(HttpMethod.POST, "/accounts").permitAll()
						.anyRequest().authenticated()
				)
				.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	FilterRegistrationBean<InternalServiceAuthenticationFilter> disableInternalAuthFilterRegistration(
			InternalServiceAuthenticationFilter filter
	) {
		FilterRegistrationBean<InternalServiceAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	FilterRegistrationBean<ApiKeyAuthenticationFilter> disableApiKeyFilterRegistration(
			ApiKeyAuthenticationFilter filter
	) {
		FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	UserDetailsService emptyUserDetailsService() {
		return new InMemoryUserDetailsManager();
	}
}
