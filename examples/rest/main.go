package main

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

func main() {
	body, err := os.ReadFile("examples/batch.json")
	if err != nil {
		panic(err)
	}

	req, err := http.NewRequest(
		"POST",
		os.Getenv("METERTRAIL_ENDPOINT")+"/v1/events/batch",
		bytes.NewReader(body),
	)
	if err != nil {
		panic(err)
	}
	req.Header.Set("Authorization", "Bearer "+os.Getenv("METERTRAIL_API_KEY"))
	req.Header.Set("Content-Type", "application/json")

	client := http.Client{Timeout: 10 * time.Second}
	r, err := client.Do(req)
	if err != nil {
		panic(err)
	}
	defer r.Body.Close()

	b, _ := io.ReadAll(r.Body)
	fmt.Println(r.Status, string(b))
	if r.StatusCode != 202 {
		os.Exit(1)
	}
}
