package uk.gov.ons.ctp.integration.rhsvc.message.impl;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import org.mockito.Mockito;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gov.ons.ctp.integration.rhsvc.config.AppConfig;
import uk.gov.ons.ctp.integration.rhsvc.event.impl.CaseEventReceiverImpl;
import uk.gov.ons.ctp.integration.rhsvc.event.impl.UACEventReceiverImpl;

@Profile("mocked-connection-factory")
@Configuration
public class EventReceiverConfiguration {

  /** Setup mock ConnectionFactory for SimpleMessageContainerListener */
  @Bean
  @Primary
  public ConnectionFactory connectionFactory() {

    Connection connection = mock(Connection.class);
    doAnswer(invocation -> mock(Channel.class)).when(connection).createChannel(anyBoolean());
    ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    when(connectionFactory.createConnection()).thenReturn(connection);
    return connectionFactory;
  }

  /** Spy on Service Activator Message End point */
  @Bean
  public CaseEventReceiverImpl caseEventReceiver() {
    return Mockito.spy(new CaseEventReceiverImpl());
  }

  /** Spy on Service Activator Message End point */
  @Bean
  public UACEventReceiverImpl uacEventReceiver(AppConfig appConfig) {
    UACEventReceiverImpl receiver = new UACEventReceiverImpl();
    ReflectionTestUtils.setField(receiver, "appConfig", appConfig);
    return Mockito.spy(receiver);
  }

  @Bean
  public AmqpAdmin amqpAdmin() {
    return mock(AmqpAdmin.class);
  }
}
