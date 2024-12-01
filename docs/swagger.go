package docs

import "github.com/swaggo/swag"

var SwaggerInfo = &swag.Spec{
	Version:          "1.0",
	Host:            "localhost:8080",
	BasePath:        "/",
	Schemes:         []string{},
	Title:           "Coach API",
	Description:     "API for the coaching and focus management service",
}
