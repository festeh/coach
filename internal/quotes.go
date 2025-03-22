package coach

import (
	"bufio"
	"os"

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
	randomIndex := 0
	return s.Quotes[randomIndex]
}
