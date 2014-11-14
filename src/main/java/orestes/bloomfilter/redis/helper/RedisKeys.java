package orestes.bloomfilter.redis.helper;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import orestes.bloomfilter.FilterBuilder;
import orestes.bloomfilter.HashProvider.HashMethod;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import backport.java.util.function.Function;

/**
 * Encapsulates the Redis keys for the Redis Bloom Filters
 */
public class RedisKeys {

    // Redis key constants
    public static final String N_KEY = "n";
    public static final String M_KEY = "m";
    public static final String K_KEY = "k";
    public static final String C_KEY = "c";
    public static final String P_KEY = "p";
    public static final String HASH_METHOD_KEY = "hashmethod";
    public final String BITS_KEY;
    public final String COUNTS_KEY;

    public RedisKeys(String instanceName) {
        this.BITS_KEY = instanceName + ":bits";
        this.COUNTS_KEY = instanceName + ":counts";
    }

    public FilterBuilder persistConfig(RedisPool pool, final FilterBuilder builder) {
        return pool.safelyReturn(new Function<Jedis, FilterBuilder>() {
            @Override
            public FilterBuilder apply(Jedis jedis) {
                FilterBuilder newConfig = null;
                // Retry on concurrent changes
                while (newConfig == null) {
                    if (!builder.overwriteIfExists() && jedis.exists(builder.name())) {
                        newConfig = RedisKeys.this.parseConfigHash(jedis.hgetAll(builder.name()), builder.name());
                    } else {
                        Map<String, String> hash = RedisKeys.this.buildConfigHash(builder);
                        jedis.watch(builder.name());
                        Transaction t = jedis.multi();
                        for (Entry<String, String> entry : hash.entrySet()) {
                            t.hset(builder.name(), entry.getKey(), entry.getValue());
                        }
                        if(builder.redisExpireAt() != null) {
                            t.expireAt(builder.name(), builder.redisExpireAt());
                        }
                        if (t.exec() != null) {
                            newConfig = builder;
                        }
                    }
                }
                return newConfig;
            }
        });
    }

    public Map<String, String> buildConfigHash(FilterBuilder config) {
        Map<String, String> map = new HashMap<>();
        map.put(P_KEY, String.valueOf(config.falsePositiveProbability()));
        map.put(M_KEY, String.valueOf(config.size()));
        map.put(K_KEY, String.valueOf(config.hashes()));
        map.put(N_KEY, String.valueOf(config.expectedElements()));
        map.put(C_KEY, String.valueOf(config.countingBits()));
        map.put(HASH_METHOD_KEY, config.hashMethod().name());
        return map;
    }

    public FilterBuilder parseConfigHash(Map<String, String> map, String name) {
        FilterBuilder config = new FilterBuilder();
        config.name(name);
        config.redisBacked(true);
        config.falsePositiveProbability(Double.valueOf(map.get(P_KEY)));
        config.size(Integer.valueOf(map.get(M_KEY)));
        config.hashes(Integer.valueOf(map.get(K_KEY)));
        config.expectedElements(Integer.valueOf(map.get(N_KEY)));
        config.countingBits(Integer.valueOf(map.get(C_KEY)));
        config.hashFunction(HashMethod.valueOf(map.get(HASH_METHOD_KEY)));
        config.complete();
        return config;
    }

}
