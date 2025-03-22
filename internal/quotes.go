package coach

import (
	"bufio"
	"math/rand"
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
	// Make sure we have quotes to return
	if len(s.Quotes) == 0 {
		return Quote{Text: "No quotes available"}
	}

	// Generate a random index between 0 and len(s.Quotes)-1
	randomIndex := rand.Intn(len(s.Quotes))
	return s.Quotes[randomIndex]
}
