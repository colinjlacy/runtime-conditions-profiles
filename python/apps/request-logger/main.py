from __future__ import annotations

import asyncio
import logging
import os
import signal

import runtimeconditions as rc

NATS_SUBJECT = "requests.received"
REDIS_LIST_KEY = "requests"

rc.MessageBus(
    "request-events",
    rc.PubSub(rc.NATS),
    rc.Subscribes(NATS_SUBJECT, rc.Payload(str)),
)

rc.Cache("request-log-cache", rc.KeyValue(rc.Redis))


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    nats_url = os.getenv("NATS_URL") or "nats://localhost:4222"
    redis_host = env_or_default("REDIS_HOST", "localhost")
    redis_port = env_as_int("REDIS_PORT", 6379)

    try:
        import nats
        import redis.asyncio as redis
    except ImportError as exc:
        raise SystemExit(f"required runtime dependency is not installed: {exc}") from exc

    nc = await nats.connect(nats_url)
    logging.info("Connected to NATS at %s", nats_url)

    redis_client = redis.Redis(host=redis_host, port=redis_port, decode_responses=True)
    await redis_client.ping()
    logging.info("Connected to Redis at %s:%d", redis_host, redis_port)

    async def handle_message(msg) -> None:
        data = msg.data.decode("utf-8")
        try:
            await redis_client.lpush(REDIS_LIST_KEY, data)
            logging.info("Stored request in Redis: %s", data)
        except Exception as exc:
            logging.warning("Failed to store message in Redis: %s", exc)

    await nc.subscribe(NATS_SUBJECT, cb=handle_message)
    logging.info("Subscribed to NATS subject: %s", NATS_SUBJECT)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    logging.info("NATS subscriber running. Press Ctrl+C to exit.")
    await stop.wait()
    logging.info("Shutting down...")

    await nc.drain()
    await redis_client.aclose()


def env_as_int(key: str, default: int) -> int:
    value = os.getenv(key)
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def env_or_default(key: str, default: str) -> str:
    return os.getenv(key) or default


if __name__ == "__main__":
    asyncio.run(main())
