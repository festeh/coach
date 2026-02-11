# Build admin frontend
build-admin:
    cd admin && npm install && npm run build

# Build Go binary
build-go: build-admin
    go build -o coach cmd/coach/main.go

# Build everything
build: build-go

# Run the server locally
run: build
    ./coach

# Dev: build and open admin page
dev: build
    xdg-open http://localhost:8080/admin/ &
    ./coach
