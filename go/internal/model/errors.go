package model

import (
	"errors"
	"fmt"
	"net/http"
)

// ErrorCode classifies domain and provider failures consistently across HTTP, Bot, and AI.
type ErrorCode string

const (
	ErrCancelled       ErrorCode = "cancelled"
	ErrDeadline        ErrorCode = "deadline"
	ErrRateLimited     ErrorCode = "rate_limited"
	ErrAuthRequired    ErrorCode = "auth_required"
	ErrUnavailable     ErrorCode = "unavailable"
	ErrUnsupported     ErrorCode = "unsupported"
	ErrInvalidResponse ErrorCode = "invalid_response"
	ErrTransport       ErrorCode = "transport"
	ErrInternal        ErrorCode = "internal"
	ErrInvalidInput    ErrorCode = "invalid_input"
	ErrUnauthorized    ErrorCode = "unauthorized"
	ErrForbidden       ErrorCode = "forbidden"
	ErrNotFound        ErrorCode = "not_found"
	ErrConflict        ErrorCode = "conflict"
)

// DomainError carries a structured code, HTTP status, retry hint, and user-safe detail message.
type DomainError struct {
	Code       ErrorCode
	Status     int
	Detail     string
	Provider   string
	RetryAfter int
	Retryable  bool
	Cause      error
}

func (e *DomainError) Error() string {
	if e == nil {
		return ""
	}
	if e.Cause != nil {
		return fmt.Sprintf("%s: %s (%v)", e.Code, e.Detail, e.Cause)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Detail)
}

func (e *DomainError) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.Cause
}

// HTTPStatus resolves the HTTP status code for the error.
func (e *DomainError) HTTPStatus() int {
	if e != nil && e.Status > 0 {
		return e.Status
	}
	if e == nil {
		return http.StatusInternalServerError
	}
	switch e.Code {
	case ErrInvalidInput:
		return http.StatusBadRequest
	case ErrUnauthorized, ErrAuthRequired:
		return http.StatusUnauthorized
	case ErrForbidden:
		return http.StatusForbidden
	case ErrNotFound:
		return http.StatusNotFound
	case ErrConflict:
		return http.StatusConflict
	case ErrRateLimited:
		return http.StatusTooManyRequests
	case ErrUnsupported:
		return http.StatusBadRequest
	case ErrUnavailable:
		return http.StatusServiceUnavailable
	case ErrDeadline:
		return http.StatusGatewayTimeout
	case ErrTransport, ErrInvalidResponse:
		return http.StatusBadGateway
	case ErrCancelled:
		return 499
	default:
		return http.StatusInternalServerError
	}
}

func NewError(code ErrorCode, status int, detail string) *DomainError {
	return &DomainError{Code: code, Status: status, Detail: detail}
}

func WrapError(code ErrorCode, status int, detail string, cause error) *DomainError {
	return &DomainError{Code: code, Status: status, Detail: detail, Cause: cause}
}

// AsDomainError extracts a *DomainError from any error chain.
func AsDomainError(err error) (*DomainError, bool) {
	var de *DomainError
	if errors.As(err, &de) {
		return de, true
	}
	return nil, false
}
