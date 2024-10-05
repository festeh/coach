package main

import (
	"bufio"
	"os"

	"golang.org/x/exp/rand"
)

type Quote struct {
	Text string
}

type QuoteStore struct {
	Quotes []Quote
}

func (s *QuoteStore) Load() error {
	path := "assets/quotes.txt"
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	reader := bufio.NewReader(f)
	scanner := bufio.NewScanner(reader)
	// read lines
	for {
		var q Quote
		if !scanner.Scan() {
			break
		}
		q.Text = scanner.Text()
		s.Quotes = append(s.Quotes, q)
	}

	return nil
}

func (s *QuoteStore) GetQuote() Quote {
	randomIndex := rand.Intn(len(s.Quotes))
	return s.Quotes[randomIndex]
}
