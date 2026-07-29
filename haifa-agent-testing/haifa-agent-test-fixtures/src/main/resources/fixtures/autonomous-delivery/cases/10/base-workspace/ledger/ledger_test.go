package ledger

import "testing"

func TestApplyBatch(t *testing.T) {
	book := New(map[string]int64{"cash": 100})
	if err := book.ApplyBatch([]Entry{{Account: "cash", Delta: -20}, {Account: "reserve", Delta: 20}}); err != nil {
		t.Fatal(err)
	}
	if book.Balance("cash") != 80 || book.Balance("reserve") != 20 {
		t.Fatalf("cash=%d reserve=%d", book.Balance("cash"), book.Balance("reserve"))
	}
}

func TestRejectsEmptyAccount(t *testing.T) {
	book := New(nil)
	if err := book.ApplyBatch([]Entry{{Account: "", Delta: 1}}); err == nil {
		t.Fatal("expected error")
	}
}
