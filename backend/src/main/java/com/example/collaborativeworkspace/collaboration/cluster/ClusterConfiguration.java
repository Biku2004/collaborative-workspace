package com.example.collaborativeworkspace.collaboration.cluster;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.transport.DocumentRoomHub;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "workspace.cluster.enabled", havingValue = "true")
public class ClusterConfiguration {
    @Bean(destroyMethod = "shutdown")
    DefaultClientResources clusterClientResources() { return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build(); }
    @Bean
    LettuceConnectionFactory clusterRedis(@Value("${workspace.cluster.redis.port:6379}") int port,
            @Value("${workspace.cluster.redis.password}") String password, DefaultClientResources resources) {
        if (port < 1 || port > 65535 || password.length() < 32) throw new IllegalArgumentException("Invalid local cluster Redis configuration");
        var server = new RedisStandaloneConfiguration("127.0.0.1", port); server.setPassword(password);
        var options = ClientOptions.builder().requestQueueSize(64).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build()).build();
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ofMillis(100)).clientOptions(options).clientResources(resources).build();
        return new LettuceConnectionFactory(server, client);
    }
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor clusterEphemeralExecutor() { return pool("cluster-publish", 1, 64); }
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor clusterDispatchExecutor(WorkspaceTelemetry telemetry) {
        var pool = pool("cluster-receive", 2, 64);
        pool.setRejectedExecutionHandler((task, executor) -> telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_DROPPED));
        return pool;
    }
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor clusterSubscriptionExecutor() { return pool("cluster-subscribe", 1, 1); }
    private static ThreadPoolExecutor pool(String name, int threads, int capacity) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean
    ClusterBridge clusterBridge(@Value("${workspace.cluster.namespace:local}") String namespace, @Value("${workspace.cluster.key}") String key,
            RedisConnectionFactory redis, @Qualifier("clusterEphemeralExecutor") ThreadPoolExecutor ephemeral,
            DocumentUpdateRepository updates, DocumentRoomHub rooms, JdbcTemplate jdbc, CollaborationFormatPolicy formats, JsonMapper json, WorkspaceTelemetry telemetry) {
        if (!namespace.matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid cluster namespace");
        telemetry.gauge("cluster.publisher.queued", ephemeral, executor -> executor.getQueue().size());
        return new ClusterBridge("workspace:" + namespace + ":v1", key, redis, ephemeral, updates, rooms, jdbc, formats, json, telemetry, Clock.systemUTC());
    }
    @Bean
    RedisMessageListenerContainer clusterListener(RedisConnectionFactory redis, ClusterBridge bridge, WorkspaceTelemetry telemetry,
            @Qualifier("clusterDispatchExecutor") ThreadPoolExecutor dispatch,
            @Qualifier("clusterSubscriptionExecutor") ThreadPoolExecutor subscription) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis); container.setTaskExecutor(dispatch); container.setSubscriptionExecutor(subscription);
        container.setRecoveryInterval(1000); container.setMaxSubscriptionRegistrationWaitingTime(1000);
        container.setErrorHandler(failure -> telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_UNAVAILABLE));
        container.setAutoStartup(false); container.addMessageListener(bridge, new ChannelTopic(bridge.topic()));
        telemetry.gauge("cluster.subscribed", container, listener -> listener.isListening() ? 1 : 0);
        telemetry.gauge("cluster.receiver.queued", dispatch, executor -> executor.getQueue().size());
        return container;
    }
    @Bean
    SubscriptionRecovery clusterRecovery(RedisMessageListenerContainer listener, WorkspaceTelemetry telemetry) { return new SubscriptionRecovery(listener, telemetry); }
    /** Retry subscription outside application startup: Redis downtime must not prevent local editing. */
    public static final class SubscriptionRecovery {
        private final RedisMessageListenerContainer listener;
        private final WorkspaceTelemetry telemetry;
        SubscriptionRecovery(RedisMessageListenerContainer listener, WorkspaceTelemetry telemetry) { this.listener = listener; this.telemetry = telemetry; }
        @Scheduled(fixedDelay = 1000)
        public void poll() {
            if (listener.isRunning()) return;
            try { listener.start(); }
            catch (RuntimeException ex) { listener.stop(); telemetry.signal(WorkspaceTelemetry.Signal.CLUSTER_UNAVAILABLE); }
        }
    }
}
