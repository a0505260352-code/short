-- One atomic fixed window: count the hit and give the bucket its expiry in the same round trip.
--
-- INCR and PEXPIRE cannot be issued as two round trips, because a process that dies between them
-- leaves a key with no TTL, and a key with no TTL is a bucket that counts forever and answers 429 to
-- that client until somebody deletes it by hand.
--
-- The limit itself is deliberately not here: the caller compares the returned count, so raising a
-- limit is a configuration change rather than a script change.
--
-- KEYS[1] = bucket, ARGV[1] = window in milliseconds
-- returns { count, remaining-ttl-milliseconds }
local count = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
    -- Covers a fresh bucket and any key that somehow lost its expiry, which is the same trap as above.
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
    ttl = ARGV[1]
end
return { count, ttl }
