// Package s3 is a minimal local fork of an AWS SDK package for the Runtime
// Conditions demo. It mirrors the shape of an SDK client and performs the
// S3 PutObject call needed by the demo without pulling in the full AWS SDK.
package s3

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
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
	if input == nil || input.Bucket == nil || *input.Bucket == "" {
		return nil, errors.New("s3 bucket is required")
	}
	if input.Key == nil || *input.Key == "" {
		return nil, errors.New("s3 object key is required")
	}
	region, accessKeyID, secretAccessKey, err := credentialsFromEnv()
	if err != nil {
		return nil, err
	}
	var body []byte
	if input.Body == nil {
		body = nil
	} else {
		body, err = io.ReadAll(input.Body)
		if err != nil {
			return nil, fmt.Errorf("read s3 body: %w", err)
		}
	}

	host := fmt.Sprintf("%s.s3.%s.amazonaws.com", *input.Bucket, region)
	canonicalURI := "/" + encodeObjectKey(*input.Key)
	endpoint := "https://" + host + canonicalURI
	request, err := http.NewRequestWithContext(ctx, http.MethodPut, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	signS3Request(request, body, host, region, accessKeyID, secretAccessKey, time.Now().UTC())

	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("s3 PutObject failed: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode > 299 {
		responseBody, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
		return nil, fmt.Errorf("s3 PutObject returned %s: %s", response.Status, strings.TrimSpace(string(responseBody)))
	}
	return &PutObjectOutput{}, nil
}

func credentialsFromEnv() (string, string, string, error) {
	region := os.Getenv("AWS_REGION")
	accessKeyID := os.Getenv("AWS_ACCESS_KEY_ID")
	secretAccessKey := os.Getenv("AWS_SECRET_ACCESS_KEY")
	for key, value := range map[string]string{
		"AWS_REGION":            region,
		"AWS_ACCESS_KEY_ID":     accessKeyID,
		"AWS_SECRET_ACCESS_KEY": secretAccessKey,
	} {
		if value == "" {
			return "", "", "", errors.New(key + " is not set")
		}
	}
	return region, accessKeyID, secretAccessKey, nil
}

func signS3Request(request *http.Request, body []byte, host, region, accessKeyID, secretAccessKey string, now time.Time) {
	payloadHash := sha256Hex(body)
	amzDate := now.Format("20060102T150405Z")
	shortDate := now.Format("20060102")
	credentialScope := shortDate + "/" + region + "/s3/aws4_request"
	signedHeaders := "host;x-amz-content-sha256;x-amz-date"

	request.Header.Set("X-Amz-Content-Sha256", payloadHash)
	request.Header.Set("X-Amz-Date", amzDate)

	canonicalHeaders := "host:" + host + "\n" +
		"x-amz-content-sha256:" + payloadHash + "\n" +
		"x-amz-date:" + amzDate + "\n"
	canonicalRequest := strings.Join(
		[]string{
			request.Method,
			request.URL.EscapedPath(),
			"",
			canonicalHeaders,
			signedHeaders,
			payloadHash,
		},
		"\n",
	)
	stringToSign := strings.Join(
		[]string{
			"AWS4-HMAC-SHA256",
			amzDate,
			credentialScope,
			sha256Hex([]byte(canonicalRequest)),
		},
		"\n",
	)
	signature := hex.EncodeToString(hmacSHA256(signingKey(secretAccessKey, shortDate, region), []byte(stringToSign)))
	request.Header.Set(
		"Authorization",
		"AWS4-HMAC-SHA256 Credential="+accessKeyID+"/"+credentialScope+
			", SignedHeaders="+signedHeaders+
			", Signature="+signature,
	)
}

func signingKey(secretAccessKey, shortDate, region string) []byte {
	dateKey := hmacSHA256([]byte("AWS4"+secretAccessKey), []byte(shortDate))
	regionKey := hmacSHA256(dateKey, []byte(region))
	serviceKey := hmacSHA256(regionKey, []byte("s3"))
	return hmacSHA256(serviceKey, []byte("aws4_request"))
}

func hmacSHA256(key, value []byte) []byte {
	hash := hmac.New(sha256.New, key)
	hash.Write(value)
	return hash.Sum(nil)
}

func sha256Hex(value []byte) string {
	sum := sha256.Sum256(value)
	return hex.EncodeToString(sum[:])
}

func encodeObjectKey(key string) string {
	parts := strings.Split(key, "/")
	for index, part := range parts {
		parts[index] = url.PathEscape(part)
	}
	return strings.Join(parts, "/")
}
