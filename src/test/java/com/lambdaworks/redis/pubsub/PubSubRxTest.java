package com.lambdaworks.redis.pubsub;

import static com.google.code.tempusfugit.temporal.Duration.millis;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.lambdaworks.redis.FastShutdown;
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;
import rx.Subscription;
import rx.observables.BlockingObservable;

import com.google.common.collect.Lists;
import com.lambdaworks.Delay;
import com.lambdaworks.Wait;
import com.lambdaworks.redis.AbstractRedisClientTest;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.pubsub.api.async.RedisPubSubAsyncCommands;
import com.lambdaworks.redis.pubsub.api.rx.ChannelMessage;
import com.lambdaworks.redis.pubsub.api.rx.PatternMessage;
import com.lambdaworks.redis.pubsub.api.rx.RedisPubSubReactiveCommands;

/**
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 16.06.15 13:38
 */
public class PubSubRxTest extends AbstractRedisClientTest implements RedisPubSubListener<String, String> {
    private RedisPubSubReactiveCommands<String, String> pubsub;
    private RedisPubSubReactiveCommands<String, String> pubsub2;

    private BlockingQueue<String> channels;
    private BlockingQueue<String> patterns;
    private BlockingQueue<String> messages;
    private BlockingQueue<Long> counts;

    private String channel = "channel0";
    private String pattern = "channel*";
    private String message = "msg!";

    @Before
    public void openPubSubConnection() throws Exception {
        pubsub = client.connectPubSub().reactive();
        pubsub2 = client.connectPubSub().reactive();
        pubsub.addListener(this);
        channels = new LinkedBlockingQueue<String>();
        patterns = new LinkedBlockingQueue<String>();
        messages = new LinkedBlockingQueue<String>();
        counts = new LinkedBlockingQueue<Long>();
    }

    @After
    public void closePubSubConnection() throws Exception {
        pubsub.close();
        pubsub2.close();
    }

    @Test
    public void observeChannels() throws Exception {
        pubsub.subscribe(channel).toBlocking().singleOrDefault(null);

        LinkedBlockingQueue<ChannelMessage<String, String>> channelMessages = new LinkedBlockingQueue<>();

        Subscription subscription = pubsub.observeChannels().doOnNext(channelMessages::add).subscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Wait.untilEquals(3, () -> channelMessages.size()).waitOrTimeout();
        assertThat(channelMessages).hasSize(3);

        subscription.unsubscribe();
        redis.publish(channel, message);
        Delay.delay(millis(500));
        assertThat(channelMessages).hasSize(3);

        ChannelMessage<String, String> channelMessage = channelMessages.take();
        assertThat(channelMessage.getChannel()).isEqualTo(channel);
        assertThat(channelMessage.getMessage()).isEqualTo(message);
    }

    @Test
    public void observeChannelsUnsubscribe() throws Exception {
        pubsub.subscribe(channel).toBlocking().singleOrDefault(null);

        LinkedBlockingQueue<ChannelMessage<String, String>> channelMessages = new LinkedBlockingQueue<>();

        pubsub.observeChannels().doOnNext(channelMessages::add).subscribe().unsubscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);

        Delay.delay(millis(500));
        assertThat(channelMessages).isEmpty();
    }

    @Test
    public void observePatterns() throws Exception {
        pubsub.psubscribe(pattern).toBlocking().singleOrDefault(null);

        LinkedBlockingQueue<PatternMessage<String, String>> patternMessages = new LinkedBlockingQueue<>();

        pubsub.observePatterns().doOnNext(patternMessages::add).subscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Wait.untilTrue(() -> patternMessages.size() == 3).waitOrTimeout();
        assertThat(patternMessages).hasSize(3);

        PatternMessage<String, String> patternMessage = patternMessages.take();
        assertThat(patternMessage.getChannel()).isEqualTo(channel);
        assertThat(patternMessage.getMessage()).isEqualTo(message);
        assertThat(patternMessage.getPattern()).isEqualTo(pattern);
    }

    @Test
    public void observePatternsWithUnsubscribe() throws Exception {
        pubsub.psubscribe(pattern).toBlocking().singleOrDefault(null);

        LinkedBlockingQueue<PatternMessage<String, String>> patternMessages = new LinkedBlockingQueue<>();

        Subscription subscription = pubsub.observePatterns().doOnNext(patternMessages::add).subscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Wait.untilTrue(() -> patternMessages.size() == 3).waitOrTimeout();
        assertThat(patternMessages).hasSize(3);
        subscription.unsubscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Delay.delay(millis(500));

        assertThat(patternMessages).hasSize(3);

    }

    @Test(timeout = 2000)
    public void message() throws Exception {
        pubsub.subscribe(channel).toBlocking().singleOrDefault(null);
        assertThat(channels.take()).isEqualTo(channel);

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void pmessage() throws Exception {
        pubsub.psubscribe(pattern).toBlocking().singleOrDefault(null);
        assertThat(patterns.take()).isEqualTo(pattern);

        redis.publish(channel, message);
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        redis.publish("channel2", "msg 2!");
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat(channels.take()).isEqualTo("channel2");
        assertThat(messages.take()).isEqualTo("msg 2!");
    }

    @Test(timeout = 2000)
    public void psubscribe() throws Exception {
        Void aVoid = pubsub.psubscribe(pattern).toBlocking().singleOrDefault(null);
        assertThat(aVoid).isNull();

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);
    }

    @Test
    public void pubsubEmptyChannels() throws Exception {
        try {
            pubsub.subscribe().toBlocking().singleOrDefault(null);
        } catch (Exception e) {
            assertThat(e).hasMessageContaining("ERR wrong number of arguments for 'subscribe' command");
        }
    }

    @Test
    public void pubsubChannels() throws Exception {
        pubsub.subscribe(channel).toBlocking().singleOrDefault(null);
        List<String> result = pubsub2.pubsubChannels().toList().toBlocking().first();
        assertThat(result).contains(channel);

    }

    @Test
    public void pubsubMultipleChannels() throws Exception {
        pubsub.subscribe(channel, "channel1", "channel3").toBlocking().singleOrDefault(null);

        List<String> result = pubsub2.pubsubChannels().toList().toBlocking().first();
        assertThat(result).contains(channel, "channel1", "channel3");

    }

    @Test
    public void pubsubChannelsWithArg() throws Exception {
        pubsub.subscribe(channel).subscribe();
        Wait.untilTrue(() -> pubsub2.pubsubChannels(pattern).filter(s -> channel.equals(s)).toBlocking().first() != null)
                .waitOrTimeout();

        String result = pubsub2.pubsubChannels(pattern).filter(s -> channel.equals(s)).toBlocking().first();
        assertThat(result).isEqualToIgnoringCase(channel);
    }

    @Test
    public void pubsubNumsub() throws Exception {

        pubsub.subscribe(channel).subscribe();
        Wait.untilEquals(1, () -> pubsub2.pubsubNumsub(channel).toList().toBlocking().first().size()).waitOrTimeout();

        Map<String, Long> result = pubsub2.pubsubNumsub(channel).toBlocking().first();
        assertThat(result).hasSize(1);
        assertThat(result.get(channel)).isEqualTo(1L);
    }

    @Test
    public void pubsubNumpat() throws Exception {

        Wait.untilEquals(0L, () -> pubsub2.pubsubNumpat().toBlocking().first()).waitOrTimeout();

        pubsub.psubscribe(pattern).subscribe();
        Wait.untilEquals(1L, () -> redis.pubsubNumpat()).waitOrTimeout();

        Long result = pubsub2.pubsubNumpat().toBlocking().first();
        assertThat(result.longValue()).isEqualTo(1L);
    }

    @Test(timeout = 2000)
    public void punsubscribe() throws Exception {
        pubsub.punsubscribe(pattern).subscribe();
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(0);

    }

    @Test(timeout = 2000)
    public void subscribe() throws Exception {
        pubsub.subscribe(channel).subscribe();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);
    }

    @Test(timeout = 2000)
    public void unsubscribe() throws Exception {
        pubsub.unsubscribe(channel).subscribe();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(0);

        pubsub.unsubscribe().toBlocking().singleOrDefault(null);

        assertThat(channels).isEmpty();
        assertThat(patterns).isEmpty();

    }

    @Test
    public void pubsubCloseOnClientShutdown() throws Exception {

        RedisClient redisClient = new RedisClient(host, port);

        RedisPubSubCommands<String, String> connection = redisClient.connectPubSub().sync();

        FastShutdown.shutdown(redisClient);

        assertThat(connection.isOpen()).isFalse();
    }

    @Test(timeout = 2000)
    public void utf8Channel() throws Exception {
        String channel = "channelλ";
        String message = "αβγ";

        pubsub.subscribe(channel).toBlocking().singleOrDefault(null);
        assertThat(channels.take()).isEqualTo(channel);

        pubsub2.publish(channel, message).subscribe();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void resubscribeChannelsOnReconnect() throws Exception {
        pubsub.subscribe(channel).subscribe();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);

        pubsub.quit().toBlocking().first();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);

        Wait.untilTrue(pubsub::isOpen).waitOrTimeout();

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void resubscribePatternsOnReconnect() throws Exception {
        pubsub.psubscribe(pattern).subscribe();
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);

        pubsub.quit().toBlocking().first();

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);

        Wait.untilTrue(pubsub::isOpen).waitOrTimeout();

        pubsub2.publish(channel, message).subscribe();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void adapter() throws Exception {
        final BlockingQueue<Long> localCounts = new LinkedBlockingQueue<Long>();

        RedisPubSubAdapter<String, String> adapter = new RedisPubSubAdapter<String, String>() {
            @Override
            public void subscribed(String channel, long count) {
                super.subscribed(channel, count);
                localCounts.add(count);
            }

            @Override
            public void unsubscribed(String channel, long count) {
                super.unsubscribed(channel, count);
                localCounts.add(count);
            }
        };

        pubsub.addListener(adapter);
        pubsub.subscribe(channel).subscribe();
        pubsub.psubscribe(pattern).subscribe();

        assertThat((long) localCounts.take()).isEqualTo(1L);

        pubsub2.publish(channel, message).subscribe();
        pubsub.punsubscribe(pattern).subscribe();
        pubsub.unsubscribe(channel).subscribe();

        assertThat((long) localCounts.take()).isEqualTo(0L);
    }

    @Test(timeout = 2000)
    public void removeListener() throws Exception {
        pubsub.subscribe(channel).subscribe();
        assertThat(channels.take()).isEqualTo(channel);

        pubsub2.publish(channel, message).subscribe();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        pubsub.removeListener(this);

        pubsub2.publish(channel, message).subscribe();
        assertThat(channels.poll(10, TimeUnit.MILLISECONDS)).isNull();
        assertThat(messages.poll(10, TimeUnit.MILLISECONDS)).isNull();
    }

    // RedisPubSubListener implementation

    @Override
    public void message(String channel, String message) {
        channels.add(channel);
        messages.add(message);
    }

    @Override
    public void message(String pattern, String channel, String message) {
        patterns.add(pattern);
        channels.add(channel);
        messages.add(message);
    }

    @Override
    public void subscribed(String channel, long count) {
        channels.add(channel);
        counts.add(count);
    }

    @Override
    public void psubscribed(String pattern, long count) {
        patterns.add(pattern);
        counts.add(count);
    }

    @Override
    public void unsubscribed(String channel, long count) {
        channels.add(channel);
        counts.add(count);
    }

    @Override
    public void punsubscribed(String pattern, long count) {
        patterns.add(pattern);
        counts.add(count);
    }

    protected <T> T first(Observable<T> observable) {
        BlockingObservable<T> blocking = observable.toBlocking();
        Iterator<T> iterator = blocking.getIterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }

    protected <T> List<T> all(Observable<T> observable) {
        BlockingObservable<T> blocking = observable.toBlocking();
        Iterator<T> iterator = blocking.getIterator();
        return Lists.newArrayList(iterator);
    }
}
