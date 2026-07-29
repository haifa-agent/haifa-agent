package dedupe

import (
	"reflect"
	"testing"
)

func TestTopDuplicates(t *testing.T) {
	got := TopDuplicates(
		[]string{" beta ", "alpha", "beta", "", "alpha", "beta", "Alpha"},
		10,
	)
	want := []Entry{{Value: "beta", Count: 3}, {Value: "alpha", Count: 2}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v want %#v", got, want)
	}
}

func TestLimitAndEmpty(t *testing.T) {
	if got := TopDuplicates([]string{"a", "a", "b", "b"}, 1); len(got) != 1 {
		t.Fatalf("got %#v", got)
	}
	if got := TopDuplicates([]string{"a", "a"}, 0); len(got) != 0 {
		t.Fatalf("got %#v", got)
	}
}
