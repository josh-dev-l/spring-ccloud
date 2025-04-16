package io.confluent.developer.spring;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.confluent.developer.avro.Hobbit;
import net.datafaker.Faker;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import org.springframework.boot.context.event.ApplicationStartedEvent;

@SpringBootApplication
// @EnableKafkaStreams
public class SpringCcloudApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringCcloudApplication.class, args);
	}

	@Bean
	NewTopic hobbitAvro() {
		return TopicBuilder.name("hobbit-avro")
				.partitions(12)
				.replicas(3)
				.build();
	}
}

@RequiredArgsConstructor
@Component
class Producer {

	private final KafkaTemplate<Integer, Hobbit> template;

	Faker faker;

	@EventListener(ApplicationStartedEvent.class)
	public void generate() {

		faker = new Faker();

		final Flux<Long> interval = Flux.interval(Duration.ofMillis(1_000));

		final Flux<String> quotes = Flux.fromStream(Stream.generate(() -> faker.hobbit().quote()));

		Flux.zip(interval, quotes)
				.map(it -> {
					int key = faker.random().nextInt(42);
					String value = it.getT2();
					// System.out.println("Sending to hobbit | key: " + key + " | value: " + value);
					return template.send("hobbit-avro", key, new Hobbit(value));
				}).blockLast();
	}
}

@Component
class Consumer {

	@KafkaListener(topics = "hobbit-avro", groupId = "spring-boot-kafka")
	public void consume(ConsumerRecord<Integer,Hobbit> record) {
		System.out.println("Received = " + record.value() + " with key " + record.key());
	}
}

// @Component
class Processor {

	@Autowired
	public void process(StreamsBuilder builder) {

		final Serde<Integer> integerSerde = Serdes.Integer();
		final Serde<String> stringSerde = Serdes.String();
		final Serde<Long> longSerde = Serdes.Long();

		//setup the source data to process
		KStream<Integer, String> textLines = builder.stream("hobbit", Consumed.with(integerSerde, stringSerde));

		//create a Ktable to process the text and do a word count by key
		KTable<String, Long> wordCounts = textLines
				.flatMapValues(value -> Arrays.asList(value.toLowerCase().split("\\W+")))
				.groupBy((key, value) -> value, Grouped.with(stringSerde, stringSerde))
				//counts the occurances of words and stores in a state store called counts
				.count(Materialized.as("counts"));

		wordCounts.toStream().to("streams-wordcount-output", Produced.with(stringSerde, longSerde));
	}
}

// @RestController
@RequiredArgsConstructor
class RestService {

	//access to kstreams instance
	private final StreamsBuilderFactoryBean factoryBean;

	// @GetMapping("/count/{word}")
	public Long getCount(@PathVariable String word) {
		final KafkaStreams kafkaStreams = factoryBean.getKafkaStreams(); 

		final ReadOnlyKeyValueStore<String, Long> counts = kafkaStreams.store(StoreQueryParameters.fromNameAndType("counts", QueryableStoreTypes.keyValueStore()));

		return counts.get(word);
	}
}