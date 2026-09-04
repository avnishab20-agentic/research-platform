-- Token bucket, one domain per key. Same algorithm as the old in-memory TokenBucket,
-- moved into Redis so every retrieval-service instance draws from the SAME bucket.
--
-- Redis runs a script start to finish with nothing interleaved, so read-check-write
-- here is atomic. That is the whole reason this is Lua and not three RestTemplate calls.
--
-- KEYS[1] = bucket key, e.g. "ratelimit:v1:thehindu.com"
-- ARGV[1] = capacity (burst)   ARGV[2] = refill tokens per second
-- returns 1 if a token was spent, 0 if the caller must back off

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])

-- Time comes from Redis, not from the caller. Every instance shares this one clock,
-- so a JVM whose clock drifts a second cannot corrupt the shared bucket.
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + tonumber(t[2]) / 1000

local data         = redis.call('HMGET', key, 'tokens', 'ts')
local tokens       = tonumber(data[1])
local lastRefillMs = tonumber(data[2])

-- Nothing stored: a domain nobody has touched is not owed any wait, so it starts full.
if tokens == nil then
  tokens = capacity
  lastRefillMs = now
end

-- Refill. Elapsed is clamped at 0 so a backwards clock jump cannot drain the bucket.
local elapsedMs = math.max(0, now - lastRefillMs)
tokens = math.min(capacity, tokens + (elapsedMs / 1000.0) * rate)

-- Not "> 0": 0.3 of a token is not enough to make a whole request.
local allowed = 0
if tokens >= 1.0 then
  tokens = tokens - 1.0
  allowed = 1
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)

-- Once capacity/rate seconds pass, an untouched bucket has refilled to full — which is
-- exactly what a missing key gives us. So past that point the key carries no information
-- and can go. ceil, and never less than 1, since EXPIRE takes whole seconds.
redis.call('EXPIRE', key, math.max(1, math.ceil(capacity / rate)))

return allowed
