package mockserver

import (
	"encoding/json"
	"strings"
)

// This file defines the streaming and protocol-specific response actions that
// mirror the Java/Python/Ruby/Node clients: Server-Sent Events, WebSocket,
// gRPC streaming, raw binary, and DNS responses.
//
// The JSON wire keys match the MockServer serialization model (sourced from
// mockserver-client-python/mockserver/models.py and the mockserver-core DTO
// classes, e.g. HttpSseResponseDTO / GrpcStreamResponseDTO / DnsRecordDTO).

// --- Server-Sent Events (SSE) response ---

// SseEvent represents a single Server-Sent Event in an SSE response stream.
// Comment is sent before the other fields as ":" followed by each of its lines
// exactly as written ("hb" sends ":hb", " hb" sends ": hb"); an event with only
// a Comment is a heartbeat that dispatches no event. Because the field
// is omitempty, an empty Comment is not sent: an event whose comment is the empty
// string (a bare ":" heartbeat) cannot be built with SseEvent and needs the JSON
// form of the expectation instead.
type SseEvent struct {
	Event   string `json:"event,omitempty"`
	Data    string `json:"data,omitempty"`
	ID      string `json:"id,omitempty"`
	Retry   int    `json:"retry,omitempty"`
	Comment string `json:"comment,omitempty"`
	Delay   *Delay `json:"delay,omitempty"`
}

// HttpSseResponse represents a Server-Sent Events response action.
type HttpSseResponse struct {
	StatusCode      int                 `json:"statusCode,omitempty"`
	Headers         map[string][]string `json:"headers,omitempty"`
	Events          []SseEvent          `json:"events,omitempty"`
	CloseConnection *bool               `json:"closeConnection,omitempty"`
	// CloseDelay is the wait after the last event before the stream ends.
	CloseDelay *Delay `json:"closeDelay,omitempty"`
	// KeepStreamOpen, when true, keeps the stream open after its last event until the client leaves; CloseDelay is then ignored.
	KeepStreamOpen *bool  `json:"keepStreamOpen,omitempty"`
	Delay          *Delay `json:"delay,omitempty"`
	Primary        *bool  `json:"primary,omitempty"`
}

// SseResponseBuilder provides a fluent API for building HttpSseResponse actions.
type SseResponseBuilder struct {
	response HttpSseResponse
}

// SseResponse creates a new SseResponseBuilder.
func SseResponse() *SseResponseBuilder {
	return &SseResponseBuilder{}
}

// StatusCode sets the HTTP status code for the SSE response.
func (b *SseResponseBuilder) StatusCode(code int) *SseResponseBuilder {
	b.response.StatusCode = code
	return b
}

// Header adds a response header.
func (b *SseResponseBuilder) Header(name string, values ...string) *SseResponseBuilder {
	if b.response.Headers == nil {
		b.response.Headers = make(map[string][]string)
	}
	b.response.Headers[name] = values
	return b
}

// Event appends an SSE event with the given event type and data.
func (b *SseResponseBuilder) Event(event, data string) *SseResponseBuilder {
	b.response.Events = append(b.response.Events, SseEvent{Event: event, Data: data})
	return b
}

// AddEvent appends a fully specified SseEvent.
func (b *SseResponseBuilder) AddEvent(e SseEvent) *SseResponseBuilder {
	b.response.Events = append(b.response.Events, e)
	return b
}

// CloseConnection sets whether the connection is closed after the events.
func (b *SseResponseBuilder) CloseConnection(close bool) *SseResponseBuilder {
	b.response.CloseConnection = &close
	return b
}

// WithDelay sets the response delay.
func (b *SseResponseBuilder) WithDelay(timeUnit string, value int) *SseResponseBuilder {
	b.response.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// CloseDelay sets the wait after the last event before the stream ends.
func (b *SseResponseBuilder) CloseDelay(timeUnit string, value int) *SseResponseBuilder {
	b.response.CloseDelay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// KeepStreamOpen sets whether the stream stays open after its last event until the client leaves.
func (b *SseResponseBuilder) KeepStreamOpen(keepOpen bool) *SseResponseBuilder {
	b.response.KeepStreamOpen = &keepOpen
	return b
}

// Primary marks this response as the primary action when multiple are present.
func (b *SseResponseBuilder) Primary(primary bool) *SseResponseBuilder {
	b.response.Primary = &primary
	return b
}

// Build returns the constructed HttpSseResponse.
func (b *SseResponseBuilder) Build() HttpSseResponse {
	return b.response
}

// --- WebSocket response ---

// WebSocketMessage represents a single WebSocket message. Binary should be a
// base64-encoded string (matching the MockServer wire format).
type WebSocketMessage struct {
	Text   string `json:"text,omitempty"`
	Binary string `json:"binary,omitempty"`
	Delay  *Delay `json:"delay,omitempty"`
}

// WebSocketMatcher is a per-incoming-frame response rule: an inbound frame of
// FrameType ("TEXT", "BINARY", "PING", "PONG" or "ANY") whose text matches
// TextMatcher triggers this rule's Responses (mirrors httpWebSocketResponse.matchers).
type WebSocketMatcher struct {
	FrameType   string             `json:"frameType,omitempty"`
	TextMatcher string             `json:"textMatcher,omitempty"`
	Responses   []WebSocketMessage `json:"responses,omitempty"`
}

// HttpWebSocketResponse represents a WebSocket response action.
type HttpWebSocketResponse struct {
	Subprotocol string             `json:"subprotocol,omitempty"`
	Messages    []WebSocketMessage `json:"messages,omitempty"`
	// Matchers are per-incoming-frame response rules; when set, an incoming
	// frame matching a rule triggers that rule's responses.
	Matchers        []WebSocketMatcher `json:"matchers,omitempty"`
	CloseConnection *bool              `json:"closeConnection,omitempty"`
	Delay           *Delay             `json:"delay,omitempty"`
	Primary         *bool              `json:"primary,omitempty"`
}

// WebSocketResponseBuilder provides a fluent API for building HttpWebSocketResponse actions.
type WebSocketResponseBuilder struct {
	response HttpWebSocketResponse
}

// WebSocketResponse creates a new WebSocketResponseBuilder.
func WebSocketResponse() *WebSocketResponseBuilder {
	return &WebSocketResponseBuilder{}
}

// Subprotocol sets the negotiated WebSocket subprotocol.
func (b *WebSocketResponseBuilder) Subprotocol(subprotocol string) *WebSocketResponseBuilder {
	b.response.Subprotocol = subprotocol
	return b
}

// TextMessage appends a text WebSocket message.
func (b *WebSocketResponseBuilder) TextMessage(text string) *WebSocketResponseBuilder {
	b.response.Messages = append(b.response.Messages, WebSocketMessage{Text: text})
	return b
}

// BinaryMessage appends a binary WebSocket message. binary must be base64-encoded.
func (b *WebSocketResponseBuilder) BinaryMessage(binary string) *WebSocketResponseBuilder {
	b.response.Messages = append(b.response.Messages, WebSocketMessage{Binary: binary})
	return b
}

// AddMessage appends a fully specified WebSocketMessage.
func (b *WebSocketResponseBuilder) AddMessage(m WebSocketMessage) *WebSocketResponseBuilder {
	b.response.Messages = append(b.response.Messages, m)
	return b
}

// CloseConnection sets whether the connection is closed after the messages.
func (b *WebSocketResponseBuilder) CloseConnection(close bool) *WebSocketResponseBuilder {
	b.response.CloseConnection = &close
	return b
}

// WithDelay sets the response delay.
func (b *WebSocketResponseBuilder) WithDelay(timeUnit string, value int) *WebSocketResponseBuilder {
	b.response.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// Primary marks this response as the primary action when multiple are present.
func (b *WebSocketResponseBuilder) Primary(primary bool) *WebSocketResponseBuilder {
	b.response.Primary = &primary
	return b
}

// Build returns the constructed HttpWebSocketResponse.
func (b *WebSocketResponseBuilder) Build() HttpWebSocketResponse {
	return b.response
}

// --- gRPC streaming response ---

// GrpcStreamMessage represents a single message in a gRPC stream. JSON is the
// JSON encoding of the protobuf message. TemplateType, when set, selects the
// template engine ("VELOCITY", "JAVASCRIPT" or "MUSTACHE") used to render JSON
// against the inbound request before sending. This type is shared by both
// GrpcStreamResponse.Messages and GrpcBidiRule.Responses.
type GrpcStreamMessage struct {
	JSON         string `json:"json,omitempty"`
	TemplateType string `json:"templateType,omitempty"`
	Delay        *Delay `json:"delay,omitempty"`
}

// GrpcStreamResponse represents a gRPC server-streaming response action.
type GrpcStreamResponse struct {
	StatusName      string              `json:"statusName,omitempty"`
	StatusMessage   string              `json:"statusMessage,omitempty"`
	Headers         map[string][]string `json:"headers,omitempty"`
	Messages        []GrpcStreamMessage `json:"messages,omitempty"`
	CloseConnection *bool               `json:"closeConnection,omitempty"`
	Delay           *Delay              `json:"delay,omitempty"`
	Primary         *bool               `json:"primary,omitempty"`
}

// GrpcStreamResponseBuilder provides a fluent API for building GrpcStreamResponse actions.
type GrpcStreamResponseBuilder struct {
	response GrpcStreamResponse
}

// GrpcStreamResponse creates a new GrpcStreamResponseBuilder.
func GrpcStream() *GrpcStreamResponseBuilder {
	return &GrpcStreamResponseBuilder{}
}

// StatusName sets the gRPC status name (e.g., "OK", "NOT_FOUND").
func (b *GrpcStreamResponseBuilder) StatusName(name string) *GrpcStreamResponseBuilder {
	b.response.StatusName = name
	return b
}

// StatusMessage sets the gRPC status message.
func (b *GrpcStreamResponseBuilder) StatusMessage(message string) *GrpcStreamResponseBuilder {
	b.response.StatusMessage = message
	return b
}

// Header adds a response header.
func (b *GrpcStreamResponseBuilder) Header(name string, values ...string) *GrpcStreamResponseBuilder {
	if b.response.Headers == nil {
		b.response.Headers = make(map[string][]string)
	}
	b.response.Headers[name] = values
	return b
}

// Message appends a gRPC stream message from its JSON encoding.
func (b *GrpcStreamResponseBuilder) Message(json string) *GrpcStreamResponseBuilder {
	b.response.Messages = append(b.response.Messages, GrpcStreamMessage{JSON: json})
	return b
}

// AddMessage appends a fully specified GrpcStreamMessage.
func (b *GrpcStreamResponseBuilder) AddMessage(m GrpcStreamMessage) *GrpcStreamResponseBuilder {
	b.response.Messages = append(b.response.Messages, m)
	return b
}

// CloseConnection sets whether the connection is closed after the messages.
func (b *GrpcStreamResponseBuilder) CloseConnection(close bool) *GrpcStreamResponseBuilder {
	b.response.CloseConnection = &close
	return b
}

// WithDelay sets the response delay.
func (b *GrpcStreamResponseBuilder) WithDelay(timeUnit string, value int) *GrpcStreamResponseBuilder {
	b.response.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// Primary marks this response as the primary action when multiple are present.
func (b *GrpcStreamResponseBuilder) Primary(primary bool) *GrpcStreamResponseBuilder {
	b.response.Primary = &primary
	return b
}

// Build returns the constructed GrpcStreamResponse.
func (b *GrpcStreamResponseBuilder) Build() GrpcStreamResponse {
	return b.response
}

// --- Binary response ---

// BinaryResponse represents a raw binary response action. BinaryData must be a
// base64-encoded string (matching the MockServer wire format).
type BinaryResponse struct {
	BinaryData string         `json:"binaryData,omitempty"`
	Delay      *Delay         `json:"delay,omitempty"`
	Primary    *bool          `json:"primary,omitempty"`
	Upstream   BinaryUpstream `json:"upstream,omitempty"`
}

// BinaryUpstream says what happens upstream to a message a binary expectation
// matches on a connection MockServer relays to an upstream
// (matchBinaryExpectationsWhenProxying). Empty means the server's default,
// BinaryUpstreamAnswerOnly. The other two need binaryMessageFraming POSTGRESQL.
type BinaryUpstream string

const (
	// BinaryUpstreamAnswerOnly writes the binary data and does not forward the message.
	BinaryUpstreamAnswerOnly BinaryUpstream = "ANSWER_ONLY"
	// BinaryUpstreamAnswerAndForward writes the binary data, forwards the message and drops the upstream's reply.
	BinaryUpstreamAnswerAndForward BinaryUpstream = "ANSWER_AND_FORWARD"
	// BinaryUpstreamForwardAndReplace forwards the message and writes the binary data in place of the upstream's reply.
	BinaryUpstreamForwardAndReplace BinaryUpstream = "FORWARD_AND_REPLACE"
)

// BinaryResponseBuilder provides a fluent API for building BinaryResponse actions.
type BinaryResponseBuilder struct {
	response BinaryResponse
}

// Binary creates a new BinaryResponseBuilder with the given base64-encoded data.
func Binary(base64Data string) *BinaryResponseBuilder {
	return &BinaryResponseBuilder{response: BinaryResponse{BinaryData: base64Data}}
}

// WithDelay sets the response delay.
func (b *BinaryResponseBuilder) WithDelay(timeUnit string, value int) *BinaryResponseBuilder {
	b.response.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// Primary marks this response as the primary action when multiple are present.
func (b *BinaryResponseBuilder) Primary(primary bool) *BinaryResponseBuilder {
	b.response.Primary = &primary
	return b
}

// WithUpstream sets what happens upstream to the matched message on a relayed connection.
func (b *BinaryResponseBuilder) WithUpstream(upstream BinaryUpstream) *BinaryResponseBuilder {
	b.response.Upstream = upstream
	return b
}

// Build returns the constructed BinaryResponse.
func (b *BinaryResponseBuilder) Build() BinaryResponse {
	return b.response
}

// --- DNS response ---

// DnsRecord represents a single DNS resource record. An MX record always
// sends its Priority, so a zero Priority is preference 0; for every other
// field a zero value means the server's default (TTL 300).
type DnsRecord struct {
	Name     string `json:"name,omitempty"`
	Type     string `json:"type,omitempty"`
	DnsClass string `json:"dnsClass,omitempty"`
	TTL      int    `json:"ttl,omitempty"`
	Value    string `json:"value,omitempty"`
	Priority int    `json:"priority,omitempty"`
	Weight   int    `json:"weight,omitempty"`
	Port     int    `json:"port,omitempty"`
}

// MarshalJSON keeps a zero MX priority on the wire; the server reads an
// absent one as preference 10.
func (r DnsRecord) MarshalJSON() ([]byte, error) {
	type plain DnsRecord
	if !strings.EqualFold(r.Type, "MX") {
		return json.Marshal(plain(r))
	}
	return json.Marshal(struct {
		plain
		Priority int `json:"priority"`
	}{plain(r), r.Priority})
}

// ARecord returns an A (IPv4) DNS record.
func ARecord(name, ip string) DnsRecord {
	return DnsRecord{Name: name, Type: "A", Value: ip}
}

// AAAARecord returns an AAAA (IPv6) DNS record.
func AAAARecord(name, ip string) DnsRecord {
	return DnsRecord{Name: name, Type: "AAAA", Value: ip}
}

// CNAMERecord returns a CNAME DNS record.
func CNAMERecord(name, cname string) DnsRecord {
	return DnsRecord{Name: name, Type: "CNAME", Value: cname}
}

// MXRecord returns an MX DNS record.
func MXRecord(name string, priority int, exchange string) DnsRecord {
	return DnsRecord{Name: name, Type: "MX", Priority: priority, Value: exchange}
}

// SRVRecord returns an SRV DNS record.
func SRVRecord(name string, priority, weight, port int, target string) DnsRecord {
	return DnsRecord{Name: name, Type: "SRV", Priority: priority, Weight: weight, Port: port, Value: target}
}

// TXTRecord returns a TXT DNS record.
func TXTRecord(name, text string) DnsRecord {
	return DnsRecord{Name: name, Type: "TXT", Value: text}
}

// PTRRecord returns a PTR DNS record.
func PTRRecord(name, pointer string) DnsRecord {
	return DnsRecord{Name: name, Type: "PTR", Value: pointer}
}

// DnsResponse represents a DNS response action.
type DnsResponse struct {
	ResponseCode      string      `json:"responseCode,omitempty"`
	AnswerRecords     []DnsRecord `json:"answerRecords,omitempty"`
	AuthorityRecords  []DnsRecord `json:"authorityRecords,omitempty"`
	AdditionalRecords []DnsRecord `json:"additionalRecords,omitempty"`
	Delay             *Delay      `json:"delay,omitempty"`
	Primary           *bool       `json:"primary,omitempty"`
}

// DnsResponseBuilder provides a fluent API for building DnsResponse actions.
type DnsResponseBuilder struct {
	response DnsResponse
}

// Dns creates a new DnsResponseBuilder.
func Dns() *DnsResponseBuilder {
	return &DnsResponseBuilder{}
}

// ResponseCode sets the DNS response code (e.g., "NOERROR", "NXDOMAIN").
func (b *DnsResponseBuilder) ResponseCode(code string) *DnsResponseBuilder {
	b.response.ResponseCode = code
	return b
}

// Answer appends one or more DNS answer records.
func (b *DnsResponseBuilder) Answer(records ...DnsRecord) *DnsResponseBuilder {
	b.response.AnswerRecords = append(b.response.AnswerRecords, records...)
	return b
}

// Authority appends one or more DNS authority records.
func (b *DnsResponseBuilder) Authority(records ...DnsRecord) *DnsResponseBuilder {
	b.response.AuthorityRecords = append(b.response.AuthorityRecords, records...)
	return b
}

// Additional appends one or more DNS additional records.
func (b *DnsResponseBuilder) Additional(records ...DnsRecord) *DnsResponseBuilder {
	b.response.AdditionalRecords = append(b.response.AdditionalRecords, records...)
	return b
}

// WithDelay sets the response delay.
func (b *DnsResponseBuilder) WithDelay(timeUnit string, value int) *DnsResponseBuilder {
	b.response.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// Primary marks this response as the primary action when multiple are present.
func (b *DnsResponseBuilder) Primary(primary bool) *DnsResponseBuilder {
	b.response.Primary = &primary
	return b
}

// Build returns the constructed DnsResponse.
func (b *DnsResponseBuilder) Build() DnsResponse {
	return b.response
}
