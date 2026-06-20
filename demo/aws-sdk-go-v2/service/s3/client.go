// Package s3 is a minimal local fork of an AWS SDK package for the Runtime
// Conditions demo. It intentionally mirrors the shape of an SDK client while
// keeping runtime behavior inert and dependency-free.
package s3

import (
	"context"
	"io"
)

// Config represents SDK configuration.
type Config struct{}

// Options represents per-client or per-operation options.
type Options struct{}

// Client provides S3 operations.
type Client struct{}

// NewFromConfig constructs an S3 client from SDK configuration.
func NewFromConfig(cfg Config, optFns ...func(*Options)) *Client {
	return &Client{}
}

// PutObjectInput is the request shape for PutObject.
type PutObjectInput struct {
	Bucket *string
	Key    *string
	Body   io.Reader
}

// PutObjectOutput is the response shape for PutObject.
type PutObjectOutput struct{}

// PutObject stores an object in an S3 bucket.
func (c *Client) PutObject(ctx context.Context, input *PutObjectInput, optFns ...func(*Options)) (*PutObjectOutput, error) {
	return &PutObjectOutput{}, nil
}
