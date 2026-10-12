# Run app in debug mode
debug:
    #!/bin/bash
    if [ -f .env ]; then
        echo "Loading environment variables from .env:"
        grep -v '^#' .env | sed 's/=.*/=***/'
        flutter run $(grep -v '^#' .env | xargs -I {} echo --dart-define={})
    else
        echo "Warning: .env file not found, running without environment variables"
        flutter run
    fi

# Build and install app in release mode
release:
    #!/bin/bash
    if [ -f .env ]; then
        echo "Loading environment variables from .env:"
        grep -v '^#' .env | sed 's/=.*/=***/'
        flutter build apk --release $(grep -v '^#' .env | xargs -I {} echo --dart-define={}) && \
        flutter install --release
    else
        echo "Warning: .env file not found, building and installing without environment variables"
        flutter build apk --release && flutter install --release
    fi