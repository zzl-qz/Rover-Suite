package registrar

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestHeartbeatNotFoundImmediatelyReregisters(t *testing.T) {
	events := make(chan requestEvent, 8)
	var heartbeatCalls int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		events <- captureRequest(t, req)
		if req.URL.Path == heartbeatPath && atomic.AddInt32(&heartbeatCalls, 1) == 1 {
			writeResponse(w, http.StatusNotFound, "INSTANCE_NOT_FOUND", "missing")
			return
		}
		writeResponse(w, http.StatusOK, "OK", "OK")
	}))
	defer server.Close()

	r := newTestRegistrar(t, server.URL, 2*time.Second)
	defer func() { _ = r.Close() }()
	if err := r.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}

	first := nextEvent(t, events)
	second := nextEventWithin(t, events, 3*time.Second)
	third := nextEvent(t, events)
	paths := []string{first.path, second.path, third.path}
	want := []string{registerPath, heartbeatPath, registerPath}
	for i := range want {
		if paths[i] != want[i] {
			t.Fatalf("request %d path = %q, want %q; all=%v", i, paths[i], want[i], paths)
		}
	}
	if first.authorization != "Bearer test-token" {
		t.Fatalf("Authorization = %q", first.authorization)
	}
	if first.body["sessionId"] != r.SessionID() || third.body["sessionId"] != r.SessionID() {
		t.Fatal("registration retry did not reuse the process session ID")
	}
	if len(r.SessionID()) != 36 || r.SessionID()[14] != '4' {
		t.Fatalf("session ID is not a UUID v4: %q", r.SessionID())
	}
}

func TestServerErrorUsesFixedDelayBeforeRegistrationRetry(t *testing.T) {
	events := make(chan requestEvent, 8)
	var registerCalls int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		events <- captureRequest(t, req)
		if req.URL.Path == registerPath && atomic.AddInt32(&registerCalls, 1) == 1 {
			writeResponse(w, http.StatusServiceUnavailable, "UNAVAILABLE", "starting")
			return
		}
		writeResponse(w, http.StatusOK, "OK", "OK")
	}))
	defer server.Close()

	interval := 40 * time.Millisecond
	r := newTestRegistrar(t, server.URL, interval)
	defer func() { _ = r.Close() }()
	if err := r.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}

	first := nextEvent(t, events)
	second := nextEvent(t, events)
	if first.path != registerPath || second.path != registerPath {
		t.Fatalf("paths = [%s %s], want two registrations", first.path, second.path)
	}
	if delay := second.at.Sub(first.at); delay < interval-3*time.Millisecond {
		t.Fatalf("retry delay = %s, want at least about %s", delay, interval)
	}
}

func TestNetworkErrorUsesFixedDelayAndOnlyOneRequestIsInFlight(t *testing.T) {
	events := make(chan requestEvent, 8)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		events <- captureRequest(t, req)
		writeResponse(w, http.StatusOK, "OK", "OK")
	}))
	defer server.Close()

	interval := 30 * time.Millisecond
	r := newTestRegistrar(t, server.URL, interval)
	var calls int32
	var active int32
	var maxActive int32
	var timesMu sync.Mutex
	var callTimes []time.Time
	delegate := http.DefaultTransport
	r.client.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		current := atomic.AddInt32(&active, 1)
		updateMax(&maxActive, current)
		defer atomic.AddInt32(&active, -1)
		timesMu.Lock()
		callTimes = append(callTimes, time.Now())
		timesMu.Unlock()
		if atomic.AddInt32(&calls, 1) == 1 {
			return nil, errors.New("dial failed")
		}
		return delegate.RoundTrip(req)
	})
	defer func() { _ = r.Close() }()
	if err := r.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}

	event := nextEvent(t, events)
	if event.path != registerPath {
		t.Fatalf("path = %q, want register", event.path)
	}
	timesMu.Lock()
	if len(callTimes) < 2 {
		timesMu.Unlock()
		t.Fatalf("transport calls = %d, want at least 2", len(callTimes))
	}
	delay := callTimes[1].Sub(callTimes[0])
	timesMu.Unlock()
	if delay < interval-3*time.Millisecond {
		t.Fatalf("network retry delay = %s, want at least about %s", delay, interval)
	}
	if got := atomic.LoadInt32(&maxActive); got != 1 {
		t.Fatalf("max in-flight requests = %d, want 1", got)
	}
}

func TestTerminalResponsesStopAndExposeError(t *testing.T) {
	statuses := []int{
		http.StatusBadRequest,
		http.StatusUnauthorized,
		http.StatusForbidden,
		http.StatusConflict,
		http.StatusRequestEntityTooLarge,
		http.StatusMethodNotAllowed,
		http.StatusUnsupportedMediaType,
	}
	for _, status := range statuses {
		t.Run(fmt.Sprintf("status_%d", status), func(t *testing.T) {
			var calls int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				atomic.AddInt32(&calls, 1)
				writeResponse(w, status, "TERMINAL", "fix configuration")
			}))
			defer server.Close()

			r := newTestRegistrar(t, server.URL, 5*time.Millisecond)
			defer func() { _ = r.Close() }()
			if err := r.Start(); err != nil {
				t.Fatalf("start: %v", err)
			}
			select {
			case <-r.Done():
			case <-time.After(time.Second):
				t.Fatal("registrar did not stop after terminal response")
			}

			var responseErr *HTTPError
			if !errors.As(r.Err(), &responseErr) {
				t.Fatalf("Err() = %v, want *HTTPError", r.Err())
			}
			if responseErr.StatusCode != status {
				t.Fatalf("status = %d, want %d", responseErr.StatusCode, status)
			}
			terminalErr, ok := <-r.Errors()
			if !ok || terminalErr == nil {
				t.Fatal("terminal error was not exposed on Errors channel")
			}
			time.Sleep(15 * time.Millisecond)
			if got := atomic.LoadInt32(&calls); got != 1 {
				t.Fatalf("request count = %d, want 1 after terminal response", got)
			}
		})
	}
}

func TestDisabledClientAPINotFoundStopsWithoutRetry(t *testing.T) {
	var calls int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		atomic.AddInt32(&calls, 1)
		writeResponse(w, http.StatusNotFound, "NOT_FOUND", "HTTP Client API is disabled")
	}))
	defer server.Close()

	r := newTestRegistrar(t, server.URL, 5*time.Millisecond)
	defer func() { _ = r.Close() }()
	if err := r.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}
	select {
	case <-r.Done():
	case <-time.After(time.Second):
		t.Fatal("registrar did not stop after API-level 404")
	}
	time.Sleep(15 * time.Millisecond)
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("request count = %d, want 1 after API-level 404", got)
	}
	var responseErr *HTTPError
	if !errors.As(r.Err(), &responseErr) || responseErr.Code != "NOT_FOUND" {
		t.Fatalf("Err() = %v, want NOT_FOUND HTTPError", r.Err())
	}
}

func TestCloseStopsWorkerAndBestEffortUnregisters(t *testing.T) {
	events := make(chan requestEvent, 8)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		events <- captureRequest(t, req)
		writeResponse(w, http.StatusOK, "OK", "OK")
	}))
	defer server.Close()

	r := newTestRegistrar(t, server.URL, 100*time.Millisecond)
	if err := r.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}
	if event := nextEvent(t, events); event.path != registerPath {
		t.Fatalf("first path = %q, want register", event.path)
	}
	if event := nextEvent(t, events); event.path != heartbeatPath {
		t.Fatalf("second path = %q, want heartbeat", event.path)
	}

	if err := r.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if event := nextEvent(t, events); event.path != unregisterPath {
		t.Fatalf("close path = %q, want unregister", event.path)
	}
	select {
	case <-r.Done():
	default:
		t.Fatal("Done was not closed")
	}
	if err := r.Start(); !errors.Is(err, ErrClosed) {
		t.Fatalf("start after close error = %v, want ErrClosed", err)
	}
}

func newTestRegistrar(t *testing.T, serverURL string, interval time.Duration) *Registrar {
	t.Helper()
	r, err := newWithTimings(Config{
		NameserverURL: serverURL,
		Token:         "test-token",
		ServiceName:   "order-service",
		InstanceID:    "127.0.0.1:8080",
		Host:          "127.0.0.1",
		Port:          8080,
		Metadata: map[string]string{
			"version": "1.0.0",
		},
	}, timings{
		reportInterval: interval,
		requestTimeout: 250 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("new registrar: %v", err)
	}
	return r
}

type requestEvent struct {
	path          string
	authorization string
	body          map[string]string
	at            time.Time
}

func captureRequest(t *testing.T, req *http.Request) requestEvent {
	t.Helper()
	defer req.Body.Close()
	var body map[string]interface{}
	if err := json.NewDecoder(req.Body).Decode(&body); err != nil {
		t.Errorf("decode request: %v", err)
	}
	stringsOnly := make(map[string]string)
	for key, value := range body {
		if stringValue, ok := value.(string); ok {
			stringsOnly[key] = stringValue
		}
	}
	return requestEvent{
		path:          req.URL.Path,
		authorization: req.Header.Get("Authorization"),
		body:          stringsOnly,
		at:            time.Now(),
	}
}

func nextEvent(t *testing.T, events <-chan requestEvent) requestEvent {
	t.Helper()
	return nextEventWithin(t, events, time.Second)
}

func nextEventWithin(t *testing.T, events <-chan requestEvent, timeout time.Duration) requestEvent {
	t.Helper()
	select {
	case event := <-events:
		return event
	case <-time.After(timeout):
		t.Fatal("timed out waiting for HTTP request")
		return requestEvent{}
	}
}

func writeResponse(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"code":    code,
		"message": message,
	})
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return fn(req)
}

func updateMax(target *int32, value int32) {
	for {
		current := atomic.LoadInt32(target)
		if current >= value || atomic.CompareAndSwapInt32(target, current, value) {
			return
		}
	}
}
