package dedupe

import (
	"sort"
	"strings"
)

type Entry struct {
	Value string `json:"value"`
	Count int    `json:"count"`
}

func TopDuplicates(lines []string, limit int) []Entry {
	if limit <= 0 {
		return []Entry{}
	}
	normalized := make([]string, 0, len(lines))
	for _, line := range lines {
		value := strings.TrimSpace(line)
		if value != "" {
			normalized = append(normalized, value)
		}
	}
	entries := make([]Entry, 0)
	for index, value := range normalized {
		alreadyAdded := false
		for _, entry := range entries {
			if entry.Value == value {
				alreadyAdded = true
				break
			}
		}
		if alreadyAdded {
			continue
		}
		count := 0
		for other := index; other < len(normalized); other++ {
			if normalized[other] == value {
				count++
			}
		}
		if count > 1 {
			entries = append(entries, Entry{Value: value, Count: count})
		}
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].Count != entries[j].Count {
			return entries[i].Count > entries[j].Count
		}
		return entries[i].Value < entries[j].Value
	})
	if len(entries) > limit {
		entries = entries[:limit]
	}
	return entries
}
