package orestes.bloomfilter.redis.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisConnectionException;
import backport.java.util.function.Consumer;
import backport.java.util.function.Function;

/**
 * Encapsulates a Connection Pool and offers convenience methods for safe access through Java 8 Lambdas.
 */
public class RedisPool {

    private final JedisPool pool;
    private List<RedisPool> slavePools;
    private Random random;

    public RedisPool(String host, int port, int redisConnections) {
        this.pool = createJedisPool(host, port, redisConnections);
    }

    public RedisPool(String host, int port, int redisConnections, Set<Entry<String, Integer>> readSlaves) {
        this(host, port, redisConnections);
        if (readSlaves != null && !readSlaves.isEmpty()) {
            slavePools = new ArrayList<>();
            random = new Random();
            for (Entry<String, Integer> slave : readSlaves) {
                slavePools.add(new RedisPool(slave.getKey(), slave.getValue(), redisConnections));
            }
        }
    }

    private JedisPool createJedisPool(String host, int port, int redisConnections) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setBlockWhenExhausted(true);
        config.setMaxTotal(redisConnections);
        return new JedisPool(config, host, port);
    }

    public RedisPool allowingSlaves() {
        if(slavePools == null)
            return this;
        int index = random.nextInt(slavePools.size());
        return slavePools.get(index);
    }

    public void safelyDo(final Consumer<Jedis> f) {
        safelyReturn(new Function<Jedis, Object>() {
            @Override
            public Object apply(Jedis jedis) {
                f.accept(jedis);
                return null;
            }
        });
    }

    public <T> T safelyReturn(Function<Jedis, T> f) {
        T result;
        Jedis jedis = pool.getResource();
        try {
            result = f.apply(jedis);
            return result;
        } catch (JedisConnectionException e) {
            if (jedis != null) {
                pool.returnBrokenResource(jedis);
                jedis = null;
            }
            throw e;
        } finally {
            if (jedis != null)
                pool.returnResource(jedis);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> transactionallyDo(final Consumer<Pipeline> f, final String... watch) {
        return (List<T>) safelyReturn(new Function<Jedis, Object>() {
            @Override
            public Object apply(Jedis jedis) {
                Pipeline p = jedis.pipelined();
                if (watch.length != 0) p.watch(watch);
                p.multi();
                f.accept(p);
                Response<List<Object>> exec = p.exec();
                p.sync();
                return exec.get();
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> transactionallyRetry(Consumer<Pipeline> f, String... watch) {
        while(true) {
            List<T> result = transactionallyDo(f, watch);
            if(result != null)
                return result;
        }
    }

    public void destroy() {
        pool.destroy();
    }
}
