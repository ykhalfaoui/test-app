package com.crok4it.audit.entry;

import com.crok4it.audit.core.MdcKeys;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Kafka entry point for audit context — equivalent of CorrelationIdFilter for HTTP.
 *
 * Reads the x-business-correlation-id header from the Kafka record and puts it in MDC.
 * Generates a UUID if the header is absent (first message in a new chain).
 * Cleans up MDC in afterRecord() to prevent leaks between consecutive Kafka records.
 *
 * Registration (add to your KafkaListenerContainerFactory):
 * <pre>{@code
 * @Bean
 * public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
 *         ConsumerFactory<String, String> cf,
 *         KafkaCorrelationInterceptor interceptor) {
 *     var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
 *     factory.setConsumerFactory(cf);
 *     factory.setRecordInterceptor(interceptor);
 *     return factory;
 * }
 * }</pre>
 *
 * NOTE: this class does NOT declare @Component — register it as a bean only when
 *       spring-kafka is on the classpath.
 */
public class KafkaCorrelationInterceptor<K, V>
        implements org.springframework.kafka.listener.RecordInterceptor<K, V> {

    public static final String CORRELATION_HEADER = "x-business-correlation-id";

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        Header header = record.headers().lastHeader(CORRELATION_HEADER);
        String correlationId = header != null
                ? new String(header.value(), StandardCharsets.UTF_8)
                : UUID.randomUUID().toString();

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA");
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        MDC.remove(MdcKeys.CORRELATION_ID);
        MDC.remove(MdcKeys.SOURCE_SYSTEM);
    }
}
