package com.example.drive.dispatch;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "drive.dispatch.enabled", havingValue = "true")
@ConditionalOnProperty(name = "drive.dispatch.publisher-enabled", havingValue = "true")
public class DispatchAmqpConfiguration {

	@Bean
	DirectExchange operationsExchange() {
		return new DirectExchange(DispatchTopology.EXCHANGE, true, false);
	}

	@Bean
	DirectExchange operationsDeadLetterExchange() {
		return new DirectExchange(DispatchTopology.DEAD_LETTER_EXCHANGE, true, false);
	}

	@Bean
	Queue operationsExecuteQueue() {
		return QueueBuilder.durable(DispatchTopology.QUEUE)
				.withArgument("x-dead-letter-exchange", DispatchTopology.DEAD_LETTER_EXCHANGE)
				.withArgument("x-dead-letter-routing-key", DispatchTopology.DEAD_LETTER_ROUTING_KEY)
				.build();
	}

	@Bean
	Queue operationsExecuteDeadLetterQueue() {
		return QueueBuilder.durable(DispatchTopology.DEAD_LETTER_QUEUE).build();
	}

	@Bean
	Binding operationsExecuteBinding(Queue operationsExecuteQueue, DirectExchange operationsExchange) {
		return BindingBuilder.bind(operationsExecuteQueue)
				.to(operationsExchange)
				.with(DispatchTopology.ROUTING_KEY);
	}

	@Bean
	Binding operationsExecuteDeadLetterBinding(
			Queue operationsExecuteDeadLetterQueue,
			DirectExchange operationsDeadLetterExchange
	) {
		return BindingBuilder.bind(operationsExecuteDeadLetterQueue)
				.to(operationsDeadLetterExchange)
				.with(DispatchTopology.DEAD_LETTER_ROUTING_KEY);
	}
}
