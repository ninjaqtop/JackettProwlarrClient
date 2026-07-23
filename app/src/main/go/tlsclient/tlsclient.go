package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"io"
	"strings"
	"unsafe"

	http "github.com/bogdanfinn/fhttp"
	tls_client "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
)

type tlsRequest struct {
	URL           string            `json:"url"`
	Method        string            `json:"method"`
	Headers       map[string]string `json:"headers"`
	Body          string            `json:"body"`
	ClientProfile string            `json:"clientProfile"`
	TimeoutMs     int               `json:"timeoutMs"`
}

type tlsResponse struct {
	StatusCode int                 `json:"statusCode"`
	Headers    map[string][]string `json:"headers"`
	Body       string              `json:"body"`
	FinalURL   string              `json:"finalUrl"`
	Error      string              `json:"error,omitempty"`
}

const maxResponseBodyBytes = 8 * 1024 * 1024

//export request
func request(reqJson *C.char) *C.char {
	if reqJson == nil {
		return jsonCString(tlsResponse{Error: "request JSON pointer is null"})
	}

	raw := C.GoString(reqJson)
	var in tlsRequest
	if err := json.Unmarshal([]byte(raw), &in); err != nil {
		return jsonCString(tlsResponse{Error: "invalid request JSON: " + err.Error()})
	}
	if strings.TrimSpace(in.URL) == "" {
		return jsonCString(tlsResponse{Error: "url is required"})
	}

	method := strings.ToUpper(strings.TrimSpace(in.Method))
	if method == "" {
		method = http.MethodGet
	}

	timeoutMs := in.TimeoutMs
	if timeoutMs <= 0 {
		timeoutMs = 30000
	}

	profile := profiles.DefaultClientProfile
	if requestedProfile := strings.TrimSpace(in.ClientProfile); requestedProfile != "" {
		if mapped, ok := profiles.MappedTLSClients[requestedProfile]; ok {
			profile = mapped
		} else {
			return jsonCString(tlsResponse{Error: "unsupported clientProfile: " + requestedProfile})
		}
	}

	client, err := tls_client.NewHttpClient(
		tls_client.NewNoopLogger(),
		tls_client.WithClientProfile(profile),
		tls_client.WithTimeoutMilliseconds(timeoutMs),
	)
	if err != nil {
		return jsonCString(tlsResponse{Error: "create TLS client failed: " + err.Error()})
	}
	defer client.CloseIdleConnections()

	var bodyReader io.Reader
	if in.Body != "" {
		bodyReader = strings.NewReader(in.Body)
	}

	req, err := http.NewRequest(method, in.URL, bodyReader)
	if err != nil {
		return jsonCString(tlsResponse{Error: "create HTTP request failed: " + err.Error()})
	}

	for key, value := range in.Headers {
		if strings.TrimSpace(key) == "" {
			continue
		}
		req.Header.Set(key, value)
	}
	if in.Body != "" && req.Header.Get("Content-Type") == "" {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}

	resp, err := client.Do(req)
	if err != nil {
		return jsonCString(tlsResponse{Error: "HTTP request failed: " + err.Error()})
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBodyBytes+1))
	if err != nil {
		return jsonCString(tlsResponse{
			StatusCode: resp.StatusCode,
			Headers:    mapHeaders(resp.Header),
			FinalURL:   finalURL(resp),
			Error:      "read response body failed: " + err.Error(),
		})
	}

	responseHeaders := mapHeaders(resp.Header)
	if len(respBody) > maxResponseBodyBytes {
		respBody = respBody[:maxResponseBodyBytes]
		responseHeaders["X-AggregatorX-Body-Truncated"] = []string{"true"}
	}

	return jsonCString(tlsResponse{
		StatusCode: resp.StatusCode,
		Headers:    responseHeaders,
		Body:       string(respBody),
		FinalURL:   finalURL(resp),
	})
}

func mapHeaders(headers http.Header) map[string][]string {
	out := make(map[string][]string, len(headers))
	for key, values := range headers {
		copied := make([]string, len(values))
		copy(copied, values)
		out[key] = copied
	}
	return out
}

func finalURL(resp *http.Response) string {
	if resp == nil || resp.Request == nil || resp.Request.URL == nil {
		return ""
	}
	return resp.Request.URL.String()
}

func jsonCString(resp tlsResponse) *C.char {
	payload, err := json.Marshal(resp)
	if err != nil {
		payload = []byte(`{"statusCode":0,"headers":{},"body":"","finalUrl":"","error":"response JSON serialization failed"}`)
	}
	return C.CString(string(payload))
}

//export free_string
func free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
