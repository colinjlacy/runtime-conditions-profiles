package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	rc "github.com/colinjlacy/golang-ast-inspection/go/runtimeconditions"
	"github.com/nats-io/nats.go"
	"github.com/redis/go-redis/v9"
)

const (
	natsSubject  = "requests.received"
	redisListKey = "requests"
)

var _ = rc.MessageBus("request-events",
	rc.PubSub(rc.NATS),
	rc.Subscribes(natsSubject, rc.Payload[string]()),
)

var _ = rc.Cache("request-log-cache", rc.KeyValue(rc.Redis))

func main() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Connect to NATS
	natsURL := os.Getenv("NATS_URL")
	if natsURL == "" {
		natsURL = "nats://localhost:4222"
	}

	nc, err := nats.Connect(natsURL)
	if err != nil {
		log.Fatalf("Failed to connect to NATS at %s: %v", natsURL, err)
	}
	defer nc.Close()
	log.Printf("Connected to NATS at %s", natsURL)

	// Connect to Redis
	redisHost := envOrDefault("REDIS_HOST", "localhost")
	redisPort := envOrDefault("REDIS_PORT", "6379")
	redisAddr := fmt.Sprintf("%s:%s", redisHost, redisPort)

	rdb := redis.NewClient(&redis.Options{
		Addr: redisAddr,
	})
	defer rdb.Close()

	// Test Redis connection
	if err := rdb.Ping(ctx).Err(); err != nil {
		log.Fatalf("Failed to connect to Redis at %s: %v", redisAddr, err)
	}
	log.Printf("Connected to Redis at %s", redisAddr)

	// Subscribe to NATS subject
	sub, err := nc.Subscribe(natsSubject, func(msg *nats.Msg) {
		// Store the message in Redis list
		if err := rdb.LPush(ctx, redisListKey, string(msg.Data)).Err(); err != nil {
			log.Printf("Failed to store message in Redis: %v", err)
		} else {
			log.Printf("Stored request in Redis: %s", string(msg.Data))
		}
	})
	if err != nil {
		log.Fatalf("Failed to subscribe to %s: %v", natsSubject, err)
	}
	defer sub.Unsubscribe()
	log.Printf("Subscribed to NATS subject: %s", natsSubject)

	// Wait for shutdown signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	log.Println("NATS subscriber running. Press Ctrl+C to exit.")
	<-sigChan
	log.Println("Shutting down...")
}

func envOrDefault(key, def string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return def
}
