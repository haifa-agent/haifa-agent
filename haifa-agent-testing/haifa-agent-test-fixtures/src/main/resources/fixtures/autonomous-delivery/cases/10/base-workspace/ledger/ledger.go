package ledger

import (
	"errors"
	"sync"
)

type Entry struct {
	Account string
	Delta   int64
}

type Ledger struct {
	balances map[string]int64
}

func New(initial map[string]int64) *Ledger {
	return &Ledger{balances: initial}
}

func (ledger *Ledger) Balance(account string) int64 {
	return ledger.balances[account]
}

func (ledger *Ledger) ApplyBatch(entries []Entry) error {
	var wait sync.WaitGroup
	for _, entry := range entries {
		if entry.Account == "" {
			return errors.New("account is required")
		}
		wait.Add(1)
		go func() {
			defer wait.Done()
			ledger.balances[entry.Account] += entry.Delta
		}()
	}
	wait.Wait()
	for account, balance := range ledger.balances {
		if balance < 0 {
			delete(ledger.balances, account)
			return errors.New("negative balance")
		}
	}
	return nil
}
