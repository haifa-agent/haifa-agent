package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"example.com/topdupes/dedupe"
)

func main() {
	limit := flag.Int("limit", 10, "maximum number of entries")
	flag.Parse()
	scanner := bufio.NewScanner(os.Stdin)
	lines := make([]string, 0)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := json.NewEncoder(os.Stdout).Encode(dedupe.TopDuplicates(lines, *limit)); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
