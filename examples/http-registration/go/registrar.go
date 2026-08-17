// Package registrar is a small, standard-library-only reference implementation
// of Rover's HTTP registration lifecycle. It is example code, not a published SDK.
package registrar

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	registerPath   = "/v1/client/instances/register"
	heartbeatPath  = "/v1/client/instances/heartbeat"
	unregisterPath = "/v1/client/instances/unregister"

	// ReportInterval is the fixed delay after a completed register or heartbeat request.
	ReportInterval = 5 * time.Second
	// RequestTimeout bounds every HTTP request, including best-effort unregister.
	RequestTimeout = 3 * time.Second

	maxResponseBytes = 64 * 1024
)

// ErrClosed is returned when Start is called after Close.
var ErrClosed = errors.New("rover registrar is closed")

// Config contains the complete instance data sent on every registration attempt.
type Config struct {
	NameserverURL string
	Token         string
	ServiceName   string
	InstanceID    string
	Host          string
	Port          int
	Weight        int
	Group         string
	Zone          string
	Metadata      map[string]string
}

// HTTPError describes a non-2xx response returned by the Nameserver.
type HTTPError struct {
	Operation  string
	StatusCode int
	Code       string
	Message    string
}

func (e *HTTPError) Error() string {
	detail := e.Message
	if detail == "" {
		detail = http.StatusText(e.StatusCode)
	}
	if e.Code != "" {
		return fmt.Sprintf("rover %s failed: http=%d code=%s message=%s",
			e.Operation, e.StatusCode, e.Code, detail)
	}
	return fmt.Sprintf("rover %s failed: http=%d message=%s",
		e.Operation, e.StatusCode, detail)
}

type timings struct {
	reportInterval time.Duration
	requestTimeout time.Duration
}

type requestOutcome uint8

const (
	outcomeSuccess requestOutcome = iota
	outcomeRetry
	outcomeNotFound
	outcomeFatal
)

type operation struct {
	name    string
	path    string
	payload []byte
}

// Registrar owns one instance's register-heartbeat-unregister lifecycle.
// A single worker goroutine serializes all requests, so at most one is in flight.
type Registrar struct {
	baseURL   string
	token     string
	session   string
	client    *http.Client
	timings   timings
	register  operation
	heartbeat operation
	unregister operation

	ctx    context.Context
	cancel context.CancelFunc

	lifecycleMu sync.Mutex
	started     bool
	closed      bool
	wg          sync.WaitGroup
	closeOnce   sync.Once
	finishOnce  sync.Once
	closeErr    error
	done        chan struct{}
	errors      chan error

	stateMu    sync.RWMutex
	lastErr    error
	fatalErr   error
}

// New validates config, generates a UUID v4 for this process session, and builds
// the Registrar. Call Start after the application port is ready to accept traffic.
func New(config Config) (*Registrar, error) {
	return newWithTimings(config, timings{
		reportInterval: ReportInterval,
		requestTimeout: RequestTimeout,
	})
}

func newWithTimings(config Config, timing timings) (*Registrar, error) {
	baseURL, err := normalizeBaseURL(config.NameserverURL)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(config.ServiceName) == "" {
		return nil, errors.New("service name is required")
	}
	if strings.TrimSpace(config.InstanceID) == "" {
		return nil, errors.New("instance ID is required")
	}
	if strings.TrimSpace(config.Host) == "" {
		return nil, errors.New("host is required")
	}
	if config.Port <= 0 || config.Port > 65535 {
		return nil, errors.New("port must be between 1 and 65535")
	}
	if timing.reportInterval <= 0 {
		return nil, errors.New("report interval must be positive")
	}
	if timing.requestTimeout <= 0 {
		return nil, errors.New("request timeout must be positive")
	}

	sessionID, err := newUUID()
	if err != nil {
		return nil, fmt.Errorf("generate session ID: %w", err)
	}
	weight := config.Weight
	if weight <= 0 {
		weight = 100
	}
	metadata := cloneMetadata(config.Metadata)
	registerPayload, err := json.Marshal(registerRequest{
		ServiceName: strings.TrimSpace(config.ServiceName),
		InstanceID:  strings.TrimSpace(config.InstanceID),
		SessionID:   sessionID,
		Host:        strings.TrimSpace(config.Host),
		Port:        config.Port,
		Weight:      weight,
		Group:       strings.TrimSpace(config.Group),
		Zone:        strings.TrimSpace(config.Zone),
		Metadata:    metadata,
	})
	if err != nil {
		return nil, fmt.Errorf("encode register request: %w", err)
	}
	sessionPayload, err := json.Marshal(sessionRequest{
		ServiceName: strings.TrimSpace(config.ServiceName),
		InstanceID:  strings.TrimSpace(config.InstanceID),
		SessionID:   sessionID,
	})
	if err != nil {
		return nil, fmt.Errorf("encode session request: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	return &Registrar{
		baseURL: baseURL,
		token:   strings.TrimSpace(config.Token),
		session: sessionID,
		client: &http.Client{
			Timeout: timing.requestTimeout,
		},
		timings: timing,
		register: operation{
			name:    "register",
			path:    registerPath,
			payload: registerPayload,
		},
		heartbeat: operation{
			name:    "heartbeat",
			path:    heartbeatPath,
			payload: sessionPayload,
		},
		unregister: operation{
			name:    "unregister",
			path:    unregisterPath,
			payload: sessionPayload,
		},
		ctx:    ctx,
		cancel: cancel,
		done:   make(chan struct{}),
		errors: make(chan error, 1),
	}, nil
}

// Start launches the worker. The first full registration request is sent immediately.
// Repeated calls are harmless; a closed Registrar cannot be restarted.
func (r *Registrar) Start() error {
	r.lifecycleMu.Lock()
	defer r.lifecycleMu.Unlock()
	if r.closed {
		return ErrClosed
	}
	if r.started {
		return nil
	}
	r.started = true
	r.wg.Add(1)
	go r.run()
	return nil
}

// SessionID returns the generated process-session UUID sent with every request.
func (r *Registrar) SessionID() string {
	return r.session
}

// Done is closed when the worker stops because of Close or a terminal response.
func (r *Registrar) Done() <-chan struct{} {
	return r.done
}

// Errors carries at most one terminal error, then closes with Done.
func (r *Registrar) Errors() <-chan error {
	return r.errors
}

// Err returns the terminal configuration/session error, if any.
func (r *Registrar) Err() error {
	r.stateMu.RLock()
	defer r.stateMu.RUnlock()
	return r.fatalErr
}

// LastError returns the latest transient or terminal request error. A successful
// register or heartbeat clears it.
func (r *Registrar) LastError() error {
	r.stateMu.RLock()
	defer r.stateMu.RUnlock()
	return r.lastErr
}

// Close stops scheduling, cancels any active request, waits for the worker, then
// attempts one unregister request if Start was called. It is idempotent. The
// unconditional best-effort request covers a lost register response after the
// Nameserver has already accepted the session.
func (r *Registrar) Close() error {
	r.closeOnce.Do(func() {
		r.lifecycleMu.Lock()
		r.closed = true
		started := r.started
		r.lifecycleMu.Unlock()

		r.cancel()
		if started {
			r.wg.Wait()
		} else {
			r.finish()
		}

		if !started {
			return
		}
		// 注册响应可能在服务端写入后丢失；即使本地尚未标记 registered，也用同一
		// session 做一次幂等注销，避免把不确定结果留到 TTL 才清理。
		ctx, cancel := context.WithTimeout(context.Background(), r.timings.requestTimeout)
		defer cancel()
		outcome, err := r.perform(ctx, r.unregister)
		if outcome == outcomeSuccess || outcome == outcomeNotFound {
			return
		}
		if err != nil {
			r.closeErr = err
		}
	})
	return r.closeErr
}

func (r *Registrar) run() {
	defer r.wg.Done()
	defer r.finish()

	registering := true
	for {
		if r.ctx.Err() != nil {
			return
		}

		op := r.heartbeat
		if registering {
			op = r.register
		}
		outcome, err := r.perform(r.ctx, op)
		if r.ctx.Err() != nil {
			return
		}

		switch outcome {
		case outcomeSuccess:
			if registering {
				registering = false
			}
			r.setLastError(nil)
			if !r.waitFixedDelay() {
				return
			}
		case outcomeNotFound:
			r.setLastError(err)
			// perform only returns this outcome for heartbeat + INSTANCE_NOT_FOUND.
			// Recover the lost lease immediately with the complete registration payload.
			registering = true
			continue
		case outcomeRetry:
			r.setLastError(err)
			if !r.waitFixedDelay() {
				return
			}
		case outcomeFatal:
			r.setFatal(err)
			return
		}
	}
}

func (r *Registrar) perform(ctx context.Context, op operation) (requestOutcome, error) {
	req, err := http.NewRequestWithContext(
		ctx, http.MethodPost, r.baseURL+op.path, bytes.NewReader(op.payload))
	if err != nil {
		return outcomeFatal, fmt.Errorf("build rover %s request: %w", op.name, err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if r.token != "" {
		req.Header.Set("Authorization", "Bearer "+r.token)
	}

	resp, err := r.client.Do(req)
	if err != nil {
		return outcomeRetry, fmt.Errorf("rover %s request failed: %w", op.name, err)
	}
	defer resp.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes+1))
	if readErr != nil {
		return outcomeRetry, fmt.Errorf("read rover %s response: %w", op.name, readErr)
	}
	if len(body) > maxResponseBytes {
		return outcomeFatal, fmt.Errorf(
			"rover %s response exceeds %d bytes", op.name, maxResponseBytes)
	}
	var envelope responseEnvelope
	decoded := json.Unmarshal(body, &envelope) == nil
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		if !decoded || envelope.Code != "OK" {
			return outcomeRetry, fmt.Errorf(
				"rover %s success response must contain code=OK", op.name)
		}
		return outcomeSuccess, nil
	}

	httpErr := &HTTPError{
		Operation:  op.name,
		StatusCode: resp.StatusCode,
		Code:       envelope.Code,
		Message:    envelope.Message,
	}
	switch resp.StatusCode {
	case http.StatusNotFound:
		if op.name == "heartbeat" && envelope.Code == "INSTANCE_NOT_FOUND" {
			return outcomeNotFound, httpErr
		}
		return outcomeFatal, httpErr
	case http.StatusRequestTimeout, http.StatusTooEarly, http.StatusTooManyRequests:
		return outcomeRetry, httpErr
	case http.StatusBadRequest,
		http.StatusUnauthorized,
		http.StatusForbidden,
		http.StatusConflict,
		http.StatusRequestEntityTooLarge:
		return outcomeFatal, httpErr
	default:
		if resp.StatusCode >= 500 {
			return outcomeRetry, httpErr
		}
		// Other 4xx/3xx responses indicate a protocol or endpoint mismatch. Repeating
		// them forever would only hide a broken integration.
		return outcomeFatal, httpErr
	}
}

func (r *Registrar) waitFixedDelay() bool {
	timer := time.NewTimer(r.timings.reportInterval)
	defer timer.Stop()
	select {
	case <-r.ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func (r *Registrar) finish() {
	r.finishOnce.Do(func() {
		close(r.done)
		close(r.errors)
	})
}

func (r *Registrar) setLastError(err error) {
	r.stateMu.Lock()
	r.lastErr = err
	r.stateMu.Unlock()
}

func (r *Registrar) setFatal(err error) {
	r.stateMu.Lock()
	if r.fatalErr == nil {
		r.fatalErr = err
		r.lastErr = err
	}
	fatal := r.fatalErr
	r.stateMu.Unlock()
	select {
	case r.errors <- fatal:
	default:
	}
}

func normalizeBaseURL(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return "", errors.New("Nameserver URL is required")
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return "", fmt.Errorf("invalid Nameserver URL: %w", err)
	}
	if (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return "", errors.New("Nameserver URL must be an absolute http(s) URL")
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errors.New("Nameserver URL must not contain a query or fragment")
	}
	return strings.TrimRight(parsed.String(), "/"), nil
}

func cloneMetadata(source map[string]string) map[string]string {
	result := make(map[string]string, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func newUUID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", err
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	raw := hex.EncodeToString(value[:])
	return raw[0:8] + "-" + raw[8:12] + "-" + raw[12:16] + "-" +
		raw[16:20] + "-" + raw[20:32], nil
}

type registerRequest struct {
	ServiceName string            `json:"serviceName"`
	InstanceID  string            `json:"instanceId"`
	SessionID   string            `json:"sessionId"`
	Host        string            `json:"host"`
	Port        int               `json:"port"`
	Weight      int               `json:"weight"`
	Group       string            `json:"group,omitempty"`
	Zone        string            `json:"zone,omitempty"`
	Metadata    map[string]string `json:"metadata"`
}

type sessionRequest struct {
	ServiceName string `json:"serviceName"`
	InstanceID  string `json:"instanceId"`
	SessionID   string `json:"sessionId"`
}

type responseEnvelope struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}
