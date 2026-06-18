package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
)

type Todo struct {
	ID        int    `json:"id"`
	Title     string `json:"title"`
	Completed bool   `json:"completed"`
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/ready", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/todos/", getTodo)

	addr := ":" + envOrDefault("PORT", "8080")
	log.Printf("todos-api listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}

func getTodo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	idPart := strings.TrimPrefix(r.URL.Path, "/todos/")
	id, err := strconv.Atoi(idPart)
	if err != nil {
		http.Error(w, "invalid todo id", http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(Todo{
		ID:        id,
		Title:     "runtime conditions demo",
		Completed: false,
	}); err != nil {
		log.Printf("encode response: %v", err)
	}
}

func envOrDefault(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}

