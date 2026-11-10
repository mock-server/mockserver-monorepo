package mockserver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// marshalToMap marshals v and unmarshals it into a generic map for key/shape assertions.
func marshalToMap(t *testing.T, v interface{}) map[string]interface{} {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("unmarshal to map failed: %v (json=%s)", err, string(data))
	}
	return m
}

// --- SSE response ---

func TestSseResponseBuilder_JSON(t *testing.T) {
	resp := SseResponse().
		StatusCode(200).
		Header("Content-Type", "text/event-stream").
		Event("message", "hello").
		AddEvent(SseEvent{Event: "update", Data: "world", ID: "42", Retry: 3000}).
		CloseConnection(true).
		Build()

	m := marshalToMap(t, resp)

	if m["statusCode"] != float64(200) {
		t.Errorf("expected statusCode 200, got %v", m["statusCode"])
	}
	if m["closeConnection"] != true {
		t.Errorf("expected closeConnection true, got %v", m["closeConnection"])
	}
	events, ok := m["events"].([]interface{})
	if !ok || len(events) != 2 {
		t.Fatalf("expected 2 events, got %v", m["events"])
	}
	first := events[0].(map[string]interface{})
	if first["event"] != "message" || first["data"] != "hello" {
		t.Errorf("unexpected first event: %v", first)
	}
	second := events[1].(map[string]interface{})
	if second["id"] != "42" || second["retry"] != float64(3000) {
		t.Errorf("unexpected second event: %v", second)
	}
}

func TestSseEvent_Comment(t *testing.T) {
	resp := SseResponse().AddEvent(SseEvent{Comment: "heartbeat"}).AddEvent(SseEvent{Data: "d"}).Build()

	events := marshalToMap(t, resp)["events"].([]interface{})
	if got := events[0].(map[string]interface{}); len(got) != 1 || got["comment"] != "heartbeat" {
		t.Errorf("expected only comment heartbeat, got %v", got)
	}
	if _, present := events[1].(map[string]interface{})["comment"]; present {
		t.Errorf("an unset comment must not be written: %v", events[1])
	}

	var decoded SseEvent
	if err := json.Unmarshal([]byte(`{"comment":"a\nb","data":"x"}`), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Comment != "a\nb" || decoded.Data != "x" {
		t.Errorf("unexpected decoded event: %+v", decoded)
	}
}

func TestSseResponse_EndOfStream(t *testing.T) {
	resp := SseResponse().Event("", "last").CloseDelay("SECONDS", 15).KeepStreamOpen(true).Build()

	m := marshalToMap(t, resp)
	closeDelay := m["closeDelay"].(map[string]interface{})
	if closeDelay["timeUnit"] != "SECONDS" || closeDelay["value"] != float64(15) {
		t.Errorf("unexpected closeDelay: %v", closeDelay)
	}
	if m["keepStreamOpen"] != true {
		t.Errorf("expected keepStreamOpen true, got %v", m["keepStreamOpen"])
	}

	var decoded HttpSseResponse
	if err := json.Unmarshal([]byte(`{"closeDelay":{"timeUnit":"MILLISECONDS","value":7002},"keepStreamOpen":false}`), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.CloseDelay == nil || decoded.CloseDelay.Value != 7002 || decoded.KeepStreamOpen == nil || *decoded.KeepStreamOpen {
		t.Errorf("unexpected decoded response: %+v", decoded)
	}

	unset := marshalToMap(t, SseResponse().Build())
	if _, present := unset["closeDelay"]; present {
		t.Errorf("an unset closeDelay must not be written: %v", unset)
	}
	if _, present := unset["keepStreamOpen"]; present {
		t.Errorf("an unset keepStreamOpen must not be written: %v", unset)
	}
}

func TestForwardChain_RespondSse_URLAndBody(t *testing.T) {
	var receivedPath string
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.When(Request().Path("/events")).
		RespondSse(SseResponse().StatusCode(200).Event("ping", "1"))
	if err != nil {
		t.Fatal(err)
	}

	if receivedPath != "/mockserver/expectation" {
		t.Errorf("expected path /mockserver/expectation, got %s", receivedPath)
	}
	var exps []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &exps); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if len(exps) != 1 {
		t.Fatalf("expected 1 expectation, got %d", len(exps))
	}
	if _, ok := exps[0]["httpSseResponse"]; !ok {
		t.Errorf("expected httpSseResponse key, got %v", exps[0])
	}
}

// --- WebSocket response ---

func TestWebSocketResponseBuilder_JSON(t *testing.T) {
	resp := WebSocketResponse().
		Subprotocol("graphql-ws").
		TextMessage("hello").
		BinaryMessage("YmluYXJ5").
		CloseConnection(true).
		Build()

	m := marshalToMap(t, resp)
	if m["subprotocol"] != "graphql-ws" {
		t.Errorf("expected subprotocol graphql-ws, got %v", m["subprotocol"])
	}
	msgs, ok := m["messages"].([]interface{})
	if !ok || len(msgs) != 2 {
		t.Fatalf("expected 2 messages, got %v", m["messages"])
	}
	if msgs[0].(map[string]interface{})["text"] != "hello" {
		t.Errorf("unexpected text message: %v", msgs[0])
	}
	if msgs[1].(map[string]interface{})["binary"] != "YmluYXJ5" {
		t.Errorf("unexpected binary message: %v", msgs[1])
	}
	if m["closeConnection"] != true {
		t.Errorf("expected closeConnection true, got %v", m["closeConnection"])
	}
}

func TestForwardChain_RespondWebSocket_Body(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.When(Request().Path("/ws")).
		RespondWebSocket(WebSocketResponse().TextMessage("hi"))
	if err != nil {
		t.Fatal(err)
	}
	var exps []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &exps); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if _, ok := exps[0]["httpWebSocketResponse"]; !ok {
		t.Errorf("expected httpWebSocketResponse key, got %v", exps[0])
	}
}

// --- gRPC stream response ---

func TestGrpcStreamResponseBuilder_JSON(t *testing.T) {
	resp := GrpcStream().
		StatusName("OK").
		StatusMessage("done").
		Header("grpc-encoding", "identity").
		Message(`{"id":1}`).
		Message(`{"id":2}`).
		CloseConnection(true).
		Build()

	m := marshalToMap(t, resp)
	if m["statusName"] != "OK" {
		t.Errorf("expected statusName OK, got %v", m["statusName"])
	}
	if m["statusMessage"] != "done" {
		t.Errorf("expected statusMessage done, got %v", m["statusMessage"])
	}
	msgs, ok := m["messages"].([]interface{})
	if !ok || len(msgs) != 2 {
		t.Fatalf("expected 2 messages, got %v", m["messages"])
	}
	if msgs[0].(map[string]interface{})["json"] != `{"id":1}` {
		t.Errorf("unexpected message json: %v", msgs[0])
	}
}

func TestForwardChain_RespondGrpcStream_Body(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.When(Request().Path("/grpc")).
		RespondGrpcStream(GrpcStream().StatusName("OK").Message(`{"x":1}`))
	if err != nil {
		t.Fatal(err)
	}
	var exps []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &exps); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if _, ok := exps[0]["grpcStreamResponse"]; !ok {
		t.Errorf("expected grpcStreamResponse key, got %v", exps[0])
	}
}

// --- Binary response ---

func TestBinaryResponseBuilder_JSON(t *testing.T) {
	resp := Binary("AQIDBA==").WithDelay("MILLISECONDS", 50).Primary(true).Build()
	m := marshalToMap(t, resp)
	if m["binaryData"] != "AQIDBA==" {
		t.Errorf("expected binaryData AQIDBA==, got %v", m["binaryData"])
	}
	if m["primary"] != true {
		t.Errorf("expected primary true, got %v", m["primary"])
	}
	delay, ok := m["delay"].(map[string]interface{})
	if !ok || delay["timeUnit"] != "MILLISECONDS" || delay["value"] != float64(50) {
		t.Errorf("unexpected delay: %v", m["delay"])
	}
}

func TestBinaryResponseBuilder_Upstream_RoundTrip(t *testing.T) {
	resp := Binary("VXBzdHJlYW0=").WithUpstream(BinaryUpstreamForwardAndReplace).Build()
	m := marshalToMap(t, resp)
	if m["upstream"] != "FORWARD_AND_REPLACE" {
		t.Errorf("expected upstream FORWARD_AND_REPLACE, got %v", m["upstream"])
	}
	data, err := json.Marshal(resp)
	if err != nil {
		t.Fatal(err)
	}
	var decoded BinaryResponse
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Upstream != BinaryUpstreamForwardAndReplace {
		t.Errorf("expected decoded upstream FORWARD_AND_REPLACE, got %q", decoded.Upstream)
	}
	if string(BinaryUpstreamAnswerOnly) != "ANSWER_ONLY" || string(BinaryUpstreamAnswerAndForward) != "ANSWER_AND_FORWARD" {
		t.Errorf("upstream constants do not match the server's names")
	}
	if _, present := marshalToMap(t, Binary("AQID").Build())["upstream"]; present {
		t.Errorf("expected upstream omitted when unset")
	}
}

func TestForwardChain_RespondBinary_Body(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.When(Request().Path("/bin")).
		RespondBinary(Binary("AQID"))
	if err != nil {
		t.Fatal(err)
	}
	var exps []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &exps); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if _, ok := exps[0]["binaryResponse"]; !ok {
		t.Errorf("expected binaryResponse key, got %v", exps[0])
	}
}

// --- DNS response ---

func TestDnsResponseBuilder_JSON(t *testing.T) {
	resp := Dns().
		ResponseCode("NOERROR").
		Answer(ARecord("example.com", "1.2.3.4"), AAAARecord("example.com", "::1")).
		Authority(CNAMERecord("www.example.com", "example.com")).
		Build()

	m := marshalToMap(t, resp)
	if m["responseCode"] != "NOERROR" {
		t.Errorf("expected responseCode NOERROR, got %v", m["responseCode"])
	}
	answers, ok := m["answerRecords"].([]interface{})
	if !ok || len(answers) != 2 {
		t.Fatalf("expected 2 answerRecords, got %v", m["answerRecords"])
	}
	a := answers[0].(map[string]interface{})
	if a["name"] != "example.com" || a["type"] != "A" || a["value"] != "1.2.3.4" {
		t.Errorf("unexpected A record: %v", a)
	}
	auth, ok := m["authorityRecords"].([]interface{})
	if !ok || len(auth) != 1 {
		t.Fatalf("expected 1 authorityRecord, got %v", m["authorityRecords"])
	}
}

func TestDnsRecordHelpers(t *testing.T) {
	tests := []struct {
		name   string
		record DnsRecord
		typ    string
		value  string
	}{
		{"A", ARecord("h", "1.1.1.1"), "A", "1.1.1.1"},
		{"AAAA", AAAARecord("h", "::1"), "AAAA", "::1"},
		{"CNAME", CNAMERecord("h", "t"), "CNAME", "t"},
		{"TXT", TXTRecord("h", "v=spf1"), "TXT", "v=spf1"},
		{"PTR", PTRRecord("h", "p"), "PTR", "p"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.record.Type != tt.typ {
				t.Errorf("expected type %s, got %s", tt.typ, tt.record.Type)
			}
			if tt.record.Value != tt.value {
				t.Errorf("expected value %s, got %s", tt.value, tt.record.Value)
			}
		})
	}

	mx := MXRecord("h", 10, "mail.example.com")
	if mx.Type != "MX" || mx.Priority != 10 || mx.Value != "mail.example.com" {
		t.Errorf("unexpected MX record: %+v", mx)
	}
	srv := SRVRecord("h", 1, 5, 8080, "target")
	if srv.Type != "SRV" || srv.Priority != 1 || srv.Weight != 5 || srv.Port != 8080 || srv.Value != "target" {
		t.Errorf("unexpected SRV record: %+v", srv)
	}
}

func TestDnsRecord_MXZeroPriorityIsSent(t *testing.T) {
	mx := marshalToMap(t, MXRecord("h", 0, "mail.example.com"))
	if p, ok := mx["priority"]; !ok || p != float64(0) {
		t.Errorf("MX priority 0 must be sent (the server reads an absent one as 10), got %v", mx)
	}
	a := marshalToMap(t, ARecord("h", "1.1.1.1"))
	if _, ok := a["priority"]; ok {
		t.Errorf("A record must not carry a priority, got %v", a)
	}
}

func TestForwardChain_RespondDns_Body(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.When(Request().Path("/dns")).
		RespondDns(Dns().Answer(ARecord("example.com", "1.2.3.4")))
	if err != nil {
		t.Fatal(err)
	}
	var exps []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &exps); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if _, ok := exps[0]["dnsResponse"]; !ok {
		t.Errorf("expected dnsResponse key, got %v", exps[0])
	}
}
