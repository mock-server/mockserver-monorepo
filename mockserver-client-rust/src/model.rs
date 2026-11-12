//! Domain model types for the MockServer control-plane API.
//!
//! All types implement `Serialize`/`Deserialize` and use builder methods that
//! take `self` and return `Self`, enabling fluent construction.

use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Free-form map used as a forward-compatibility safety net on the wire types
/// that model MockServer actions. Any JSON field the typed model does not yet
/// name is captured here (via `#[serde(flatten)]`) so it survives a
/// deserialize-then-serialize round-trip instead of being silently dropped.
///
/// An empty map contributes no keys when serialized, so a `flatten`ed `Extra`
/// is invisible on the wire unless the server actually sent unknown fields.
pub type Extra = serde_json::Map<String, serde_json::Value>;

/// Deserialize a MockServer `oneOf: [ <T>, [ <T> ] ]` field (a single object or
/// an array of objects) into a `Vec<T>`. Used by `beforeActions`, `afterActions`
/// and `capture`, which the server accepts in either shape.
fn one_or_many<'de, D, T>(deserializer: D) -> std::result::Result<Option<Vec<T>>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum OneOrMany<T> {
        One(T),
        Many(Vec<T>),
    }
    let opt = Option::<OneOrMany<T>>::deserialize(deserializer)?;
    Ok(opt.map(|v| match v {
        OneOrMany::One(t) => vec![t],
        OneOrMany::Many(v) => v,
    }))
}

// ---------------------------------------------------------------------------
// ParameterValues
// ---------------------------------------------------------------------------

/// The value of a single key in a MockServer keyToMultiValue matcher (path
/// parameters, and in general query-string parameters / headers).
///
/// MockServer accepts two wire encodings for a key's value:
///   * the **plain** form — a list of exact-or-regex strings (`["42", "^\\d+$"]`),
///     modelled by [`Values`](Self::Values); and
///   * the **schema-matcher** form — a list of matcher objects
///     (`[{ "schema": { … } }]`, `[{ "not": true, "value": "x" }]`), or the
///     `{ "parameterStyle": …, "values": [ … ] }` object form — captured verbatim
///     by [`Matcher`](Self::Matcher) so no field is dropped on a round-trip.
///
/// The enum is `#[serde(untagged)]`: the plain string-array form deserialises to
/// [`Values`](Self::Values); anything else (a matcher-object array, an object, or
/// even a bare string) falls through to [`Matcher`](Self::Matcher).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
#[non_exhaustive]
pub enum ParameterValues {
    /// Plain multi-value form: a list of exact-or-regex string values.
    Values(Vec<String>),
    /// Schema / nottable / optional / parameter-style matcher form, kept verbatim.
    Matcher(serde_json::Value),
}

impl ParameterValues {
    /// Borrow the plain string values, if this is the [`Values`](Self::Values) form.
    pub fn as_values(&self) -> Option<&[String]> {
        match self {
            ParameterValues::Values(v) => Some(v),
            ParameterValues::Matcher(_) => None,
        }
    }
}

impl From<Vec<String>> for ParameterValues {
    fn from(values: Vec<String>) -> Self {
        ParameterValues::Values(values)
    }
}

// ---------------------------------------------------------------------------
// MatcherValue
// ---------------------------------------------------------------------------

/// The markers MockServer strips from the front of a plain matcher string:
/// `!` negates the matcher and `?` makes it optional.
const NOT_CHAR: char = '!';
const OPTIONAL_CHAR: char = '?';

/// A single matcher value for a request header, query-string parameter, cookie
/// or path parameter.
///
/// MockServer's plain-string wire form encodes negation and optionality as
/// leading markers — `!` negates and `?` marks optional — and the server strips
/// them unconditionally when reading. A value whose own first character is `!`
/// or `?` therefore cannot be sent as a bare string:
/// [`header("X-Tag", "!foo")`](HttpRequest::header) is read back as "`X-Tag` is
/// anything but `foo`", which matches almost every request, so the expectation
/// silently passes for the wrong reason instead of failing.
///
/// `MatcherValue` keeps the value and the flags apart and serialises to the
/// object form (`{"not":false,"value":"!foo"}`) only when the plain form would
/// be misread. Every value that already round-trips through the plain form stays
/// byte-identical on the wire, so existing expectations are unaffected.
///
/// Use [`literal`](Self::literal) for an exact value, [`not_literal`](Self::not_literal)
/// to negate one, and [`optional_literal`](Self::optional_literal) for an
/// optional exact value:
///
/// ```
/// use mockserver_client::{HttpRequest, MatcherValue};
///
/// let request = HttpRequest::new()
///     .header_matcher("X-Tag", MatcherValue::literal("!foo"))   // X-Tag IS "!foo"
///     .header_matcher("X-Env", MatcherValue::not_literal("dev")); // X-Env is NOT "dev"
/// ```
///
/// [`before`](Self::before), [`after`](Self::after) and
/// [`equal_to_date_time`](Self::equal_to_date_time) build a date-time matcher,
/// which compares the actual value as a date-time and is sent in the object
/// form (`{"after":"now -1 hours"}`):
///
/// ```
/// use mockserver_client::{HttpRequest, MatcherValue};
///
/// let request = HttpRequest::new()
///     .header_matcher("X-Issued-At", MatcherValue::after("now -1 hours"))
///     .cookie_matcher(
///         "seen",
///         MatcherValue::before("2026-10-21T00:00:00Z").with_actual_format("rfc1123"),
///     );
/// ```
///
/// A date-time matcher is stored in [`value`](Self::value) in an internal
/// encoding (the fields of this struct are public, so a new field would break
/// struct literals); inspect it with [`is_date_time`](Self::is_date_time) rather
/// than reading `value`.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct MatcherValue {
    /// The matcher text, taken verbatim — markers in it are not parsed.
    pub value: String,
    /// Negate the matcher (`"not": true`).
    pub not: bool,
    /// Mark the header/parameter/cookie as optional (`"optional": true`).
    pub optional: bool,
}

/// Mirrors Java's `StringUtils.isBlank`: empty or whitespace only.
fn is_blank(s: &str) -> bool {
    s.trim().is_empty()
}

/// Marks a [`MatcherValue::value`] that holds an encoded [`DateTimeSpec`]. NUL
/// cannot appear in a real header, cookie or parameter value, so the marker
/// cannot collide with a literal the user meant.
const DATE_TIME_PREFIX: &str = "\u{0}mockserver-date-time\u{0}";

/// The date-time object form minus `not` (carried by [`MatcherValue::not`]).
/// Field order mirrors the Java serializer; exactly one operator is set.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DateTimeSpec {
    #[serde(skip_serializing_if = "Option::is_none")]
    before: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    after: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    equal_to_date_time: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    actual_format: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    truncate_expected: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    truncate_actual: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    apply_truncation_last: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    zone: Option<String>,
}

impl DateTimeSpec {
    fn operator_count(&self) -> usize {
        [&self.before, &self.after, &self.equal_to_date_time]
            .iter()
            .filter(|o| o.is_some())
            .count()
    }

    fn encode(&self) -> String {
        let json = serde_json::to_string(self)
            .expect("BUG: DateTimeSpec serialization failed for an in-memory value");
        format!("{DATE_TIME_PREFIX}{json}")
    }

    fn decode(value: &str) -> Option<Self> {
        let spec: Self = serde_json::from_str(value.strip_prefix(DATE_TIME_PREFIX)?).ok()?;
        (spec.operator_count() == 1).then_some(spec)
    }
}

impl MatcherValue {
    /// A matcher for exactly `value`, even when it starts with `!` or `?`.
    pub fn literal(value: impl Into<String>) -> Self {
        Self {
            value: value.into(),
            not: false,
            optional: false,
        }
    }

    /// A matcher that matches anything except exactly `value`.
    pub fn not_literal(value: impl Into<String>) -> Self {
        Self {
            value: value.into(),
            not: true,
            optional: false,
        }
    }

    /// A matcher for exactly `value` that need not be present.
    pub fn optional_literal(value: impl Into<String>) -> Self {
        Self {
            value: value.into(),
            not: false,
            optional: true,
        }
    }

    fn date_time(spec: DateTimeSpec) -> Self {
        Self {
            value: spec.encode(),
            not: false,
            optional: false,
        }
    }

    /// A date-time matcher that matches a value strictly before `expected`:
    /// `now`, `now <offset>` (e.g. `now -1 hours`), or an ISO-8601, RFC-1123 or
    /// epoch date-time. The server validates the text.
    pub fn before(expected: impl Into<String>) -> Self {
        Self::date_time(DateTimeSpec {
            before: Some(expected.into()),
            ..DateTimeSpec::default()
        })
    }

    /// A date-time matcher that matches a value strictly after `expected`. See
    /// [`before`](Self::before) for the accepted forms.
    pub fn after(expected: impl Into<String>) -> Self {
        Self::date_time(DateTimeSpec {
            after: Some(expected.into()),
            ..DateTimeSpec::default()
        })
    }

    /// A date-time matcher that matches a value equal to `expected`. See
    /// [`before`](Self::before) for the accepted forms.
    pub fn equal_to_date_time(expected: impl Into<String>) -> Self {
        Self::date_time(DateTimeSpec {
            equal_to_date_time: Some(expected.into()),
            ..DateTimeSpec::default()
        })
    }

    /// Whether this is a date-time matcher built by [`before`](Self::before),
    /// [`after`](Self::after) or [`equal_to_date_time`](Self::equal_to_date_time),
    /// or decoded from the date-time object form.
    pub fn is_date_time(&self) -> bool {
        self.date_time_spec().is_some()
    }

    fn date_time_spec(&self) -> Option<DateTimeSpec> {
        DateTimeSpec::decode(&self.value)
    }

    /// Apply `f` to the date-time options of a date-time matcher.
    ///
    /// # Panics
    /// Panics if this is not a date-time matcher, so a misplaced option is never silently dropped.
    fn map_date_time(mut self, option: &str, f: impl FnOnce(&mut DateTimeSpec)) -> Self {
        let Some(mut spec) = self.date_time_spec() else {
            panic!("{option} applies only to a date-time matcher; build one with MatcherValue::before, after or equal_to_date_time");
        };
        f(&mut spec);
        self.value = spec.encode();
        self
    }

    /// The format of the ACTUAL value (e.g. `rfc1123`, `dd/MM/yyyy`).
    ///
    /// # Panics
    /// Panics if this is not a date-time matcher.
    pub fn with_actual_format(self, actual_format: impl Into<String>) -> Self {
        let actual_format = actual_format.into();
        self.map_date_time("with_actual_format", |s| {
            s.actual_format = Some(actual_format)
        })
    }

    /// Truncate the EXPECTED date-time before comparing (e.g. `startOfDay`).
    ///
    /// # Panics
    /// Panics if this is not a date-time matcher.
    pub fn with_truncate_expected(self, truncate_expected: impl Into<String>) -> Self {
        let truncate_expected = truncate_expected.into();
        self.map_date_time("with_truncate_expected", |s| {
            s.truncate_expected = Some(truncate_expected)
        })
    }

    /// Truncate the ACTUAL date-time before comparing (e.g. `startOfDay`).
    ///
    /// # Panics
    /// Panics if this is not a date-time matcher.
    pub fn with_truncate_actual(self, truncate_actual: impl Into<String>) -> Self {
        let truncate_actual = truncate_actual.into();
        self.map_date_time("with_truncate_actual", |s| {
            s.truncate_actual = Some(truncate_actual)
        })
    }

    /// Apply truncation after, rather than before, the `now` offset.
    ///
    /// # Panics
    /// Panics if this is not a date-time matcher.
    pub fn with_apply_truncation_last(self, apply_truncation_last: bool) -> Self {
        self.map_date_time("with_apply_truncation_last", |s| {
            s.apply_truncation_last = Some(apply_truncation_last)
        })
    }

    /// The time zone used to interpret date-times without one (e.g.
    /// `Europe/London`).
    ///
    /// # Panics
    /// Panics if this is not a date-time matcher.
    pub fn with_zone(self, zone: impl Into<String>) -> Self {
        let zone = zone.into();
        self.map_date_time("with_zone", |s| s.zone = Some(zone))
    }

    /// Negate the matcher. Sets [`not`](Self::not) on any value, date-time or not.
    pub fn with_not(mut self, not: bool) -> Self {
        self.not = not;
        self
    }

    /// Render the plain-string form, matching `NottableString.serialise()`.
    fn serialise(&self) -> String {
        let mut s = String::new();
        if self.optional {
            s.push(OPTIONAL_CHAR);
        }
        if self.not {
            s.push(NOT_CHAR);
        }
        if !is_blank(&self.value) {
            s.push_str(&self.value);
        }
        s
    }

    /// Parse a plain wire string exactly as the server would, mirroring
    /// `NottableString.string(String)`: strip an optional marker, then a not
    /// marker, then an optional marker again.
    fn parse_plain(s: &str) -> Self {
        let mut optional = false;
        let mut not = false;
        let mut rest = s;
        if !is_blank(s) {
            if let Some(r) = rest.strip_prefix(OPTIONAL_CHAR) {
                optional = true;
                rest = r;
            }
            if let Some(r) = rest.strip_prefix(NOT_CHAR) {
                not = true;
                rest = r;
            }
            if let Some(r) = rest.strip_prefix(OPTIONAL_CHAR) {
                optional = true;
                rest = r;
            }
        }
        Self {
            value: rest.to_string(),
            not,
            optional,
        }
    }

    /// Whether re-reading the plain form would change the value, the negation or
    /// the optionality. Decided by actually re-parsing rather than by testing
    /// for a leading marker, so it stays correct for compound markers (`?!`,
    /// `!?`) and any marker added later.
    fn ambiguous(&self) -> bool {
        if self.is_date_time() {
            // Has no plain-string form; it must be sent as an object.
            return true;
        }
        if is_blank(&self.value) {
            // A blank value has no marker to misread, and it cannot be expressed
            // in the object form either: the server's object-form reader ignores
            // a blank "value", so escaping one would silently drop the matcher.
            return false;
        }
        let reparsed = Self::parse_plain(&self.serialise());
        reparsed.not != self.not
            || reparsed.optional != self.optional
            || reparsed.value != self.value
    }
}

impl From<String> for MatcherValue {
    /// Parse a plain wire string, so an existing `"!foo"` keeps meaning "not
    /// foo". The meaning is preserved; only the representation changes.
    fn from(s: String) -> Self {
        Self::parse_plain(&s)
    }
}

impl From<&str> for MatcherValue {
    fn from(s: &str) -> Self {
        Self::parse_plain(s)
    }
}

impl std::fmt::Display for MatcherValue {
    /// The plain-string form, or the JSON object form for a date-time matcher.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.is_date_time() {
            let json = serde_json::to_string(self).map_err(|_| std::fmt::Error)?;
            return f.write_str(&json);
        }
        f.write_str(&self.serialise())
    }
}

impl Serialize for MatcherValue {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        use serde::ser::SerializeMap;
        if let Some(spec) = self.date_time_spec() {
            // Like Java's writeDateTime: "not" only when true, then the operator
            // and the options that are set. "optional" is not part of the form;
            // it is written only if set so the server rejects it loudly.
            let mut map = serializer.serialize_map(None)?;
            if self.not {
                map.serialize_entry("not", &true)?;
            }
            let strings = [
                ("before", &spec.before),
                ("after", &spec.after),
                ("equalToDateTime", &spec.equal_to_date_time),
                ("actualFormat", &spec.actual_format),
                ("truncateExpected", &spec.truncate_expected),
                ("truncateActual", &spec.truncate_actual),
            ];
            for (key, value) in strings {
                if let Some(value) = value {
                    map.serialize_entry(key, value)?;
                }
            }
            if let Some(last) = spec.apply_truncation_last {
                map.serialize_entry("applyTruncationLast", &last)?;
            }
            if let Some(zone) = &spec.zone {
                map.serialize_entry("zone", zone)?;
            }
            if self.optional {
                map.serialize_entry("optional", &true)?;
            }
            return map.end();
        }
        if !self.ambiguous() {
            return serializer.serialize_str(&self.serialise());
        }
        // "not" is written even when false so the intent is unmistakable to a
        // reader and to the other clients, rather than relying on absent==false.
        let count = if self.optional { 3 } else { 2 };
        let mut map = serializer.serialize_map(Some(count))?;
        map.serialize_entry("not", &self.not)?;
        if self.optional {
            map.serialize_entry("optional", &self.optional)?;
        }
        map.serialize_entry("value", &self.value)?;
        map.end()
    }
}

impl<'de> Deserialize<'de> for MatcherValue {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        // A hand-written visitor rather than an `#[serde(untagged)]` enum: an
        // untagged enum re-buffers into `Content`, which does not compose with
        // the `Content` buffering that `#[serde(flatten)]` already applies to
        // the enclosing `HttpRequest`, so the object form is misread as a string
        // inside a flattened struct. `visit_str`/`visit_map` dispatch directly
        // and work in both contexts.
        struct MatcherValueVisitor;

        impl<'de> serde::de::Visitor<'de> for MatcherValueVisitor {
            type Value = MatcherValue;

            fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str(
                    "a matcher string, an object with a \"value\" field, or a date-time \
                     object with \"before\", \"after\" or \"equalToDateTime\"",
                )
            }

            fn visit_str<E>(self, v: &str) -> std::result::Result<MatcherValue, E>
            where
                E: serde::de::Error,
            {
                Ok(MatcherValue::parse_plain(v))
            }

            fn visit_map<A>(self, mut map: A) -> std::result::Result<MatcherValue, A::Error>
            where
                A: serde::de::MapAccess<'de>,
            {
                let mut value: Option<String> = None;
                let mut not = false;
                let mut optional = false;
                let mut date_time = DateTimeSpec::default();
                while let Some(key) = map.next_key::<String>()? {
                    match key.as_str() {
                        "value" => value = Some(map.next_value()?),
                        "not" => not = map.next_value()?,
                        "optional" => optional = map.next_value()?,
                        "before" => date_time.before = Some(map.next_value()?),
                        "after" => date_time.after = Some(map.next_value()?),
                        "equalToDateTime" => date_time.equal_to_date_time = Some(map.next_value()?),
                        "actualFormat" => date_time.actual_format = Some(map.next_value()?),
                        "truncateExpected" => date_time.truncate_expected = Some(map.next_value()?),
                        "truncateActual" => date_time.truncate_actual = Some(map.next_value()?),
                        "applyTruncationLast" => {
                            date_time.apply_truncation_last = Some(map.next_value()?)
                        }
                        "zone" => date_time.zone = Some(map.next_value()?),
                        // Ignore any other key so the object form stays
                        // forward-compatible with fields added later.
                        _ => {
                            let _ = map.next_value::<serde::de::IgnoredAny>()?;
                        }
                    }
                }
                match date_time.operator_count() {
                    0 => {}
                    1 if value.is_none() => {
                        return Ok(MatcherValue {
                            value: date_time.encode(),
                            not,
                            optional,
                        });
                    }
                    1 => {
                        return Err(serde::de::Error::custom(
                            "a date-time matcher cannot also have a \"value\"",
                        ));
                    }
                    _ => {
                        return Err(serde::de::Error::custom(
                            "a date-time matcher needs exactly one of \"before\", \"after\" \
                             or \"equalToDateTime\"",
                        ));
                    }
                }
                let value = value.ok_or_else(|| serde::de::Error::missing_field("value"))?;
                Ok(MatcherValue {
                    value,
                    not,
                    optional,
                })
            }
        }

        deserializer.deserialize_any(MatcherValueVisitor)
    }
}

/// Lenient deserializer for `path` / `method`, which this model represents as
/// plain strings. When an expectation carries their object (nottable) form —
/// which the plain-string field cannot hold — the object is discarded rather
/// than failing the whole expectation, so the rest of the request (headers,
/// query parameters, the enclosing response, …) still decodes. The dropped
/// field is recorded in the round-trip fidelity harness's known-gaps ledger as
/// `httpRequest.path` / `httpRequest.method`.
fn de_lenient_optional_string<'de, D>(
    deserializer: D,
) -> std::result::Result<Option<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    struct LenientString;

    impl<'de> serde::de::Visitor<'de> for LenientString {
        type Value = Option<String>;

        fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("a string, or an object matcher form that is discarded")
        }

        fn visit_str<E>(self, v: &str) -> std::result::Result<Option<String>, E>
        where
            E: serde::de::Error,
        {
            Ok(Some(v.to_owned()))
        }

        fn visit_none<E>(self) -> std::result::Result<Option<String>, E>
        where
            E: serde::de::Error,
        {
            Ok(None)
        }

        fn visit_unit<E>(self) -> std::result::Result<Option<String>, E>
        where
            E: serde::de::Error,
        {
            Ok(None)
        }

        fn visit_some<D>(self, deserializer: D) -> std::result::Result<Option<String>, D::Error>
        where
            D: serde::Deserializer<'de>,
        {
            deserializer.deserialize_any(self)
        }

        fn visit_map<A>(self, mut map: A) -> std::result::Result<Option<String>, A::Error>
        where
            A: serde::de::MapAccess<'de>,
        {
            // The object (nottable) form cannot be represented as a plain string;
            // drain it and discard so the surrounding decode still succeeds.
            while map
                .next_entry::<serde::de::IgnoredAny, serde::de::IgnoredAny>()?
                .is_some()
            {}
            Ok(None)
        }
    }

    deserializer.deserialize_any(LenientString)
}

// ---------------------------------------------------------------------------
// HttpRequest
// ---------------------------------------------------------------------------

/// Matcher for an HTTP request. Uses builder methods for fluent construction.
///
/// # Example
/// ```
/// use mockserver_client::HttpRequest;
///
/// let request = HttpRequest::new()
///     .method("POST")
///     .path("/api/users")
///     .header("Content-Type", "application/json")
///     .query_param("page", "1")
///     .body("{}");
/// ```
/// `Serialize`/`Deserialize` are implemented by hand (see below) rather than
/// derived, so the additive `*_matchers` fields can stand in for their plain
/// counterparts under the same wire key — the same shape the Go client uses.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct HttpRequest {
    pub method: Option<String>,

    pub path: Option<String>,

    /// Query-string parameters to match, multiple values per key (plain form).
    pub query_string_parameters: Option<HashMap<String, Vec<String>>>,

    /// Headers to match, multiple values per key (plain form).
    pub headers: Option<HashMap<String, Vec<String>>>,

    pub body: Option<Body>,

    pub jwt: Option<Jwt>,

    pub socket_address: Option<SocketAddress>,

    /// Negate the whole request matcher (`"not": true`).
    pub not: Option<bool>,

    /// Match only requests received over TLS (`"secure"`).
    pub secure: Option<bool>,

    /// Match only keep-alive requests (`"keepAlive"`).
    pub keep_alive: Option<bool>,

    /// Match the request protocol (e.g. `"HTTP_1_1"`, `"HTTP_2"`, `"HTTP_3"`).
    pub protocol: Option<String>,

    /// Path parameters (`/users/{id}` style), multiple values per key.
    ///
    /// Each key's value is a [`ParameterValues`], accepting both the plain
    /// string-list form and the schema/nottable matcher form.
    pub path_parameters: Option<HashMap<String, ParameterValues>>,

    /// Cookies to match (single value per name, plain form).
    pub cookies: Option<HashMap<String, String>>,

    /// Header matchers whose values are taken verbatim, expressing a value that
    /// the plain [`headers`](Self::headers) map cannot: one starting with `!` or
    /// `?`. When non-empty this REPLACES [`headers`](Self::headers) on the wire
    /// under the same `headers` key. Additive — the plain map keeps its type and
    /// meaning. Populate via [`header_matcher`](Self::header_matcher).
    pub header_matchers: Option<HashMap<String, Vec<MatcherValue>>>,

    /// Query-string parameter matchers. See [`header_matchers`](Self::header_matchers)
    /// and [`query_param_matcher`](Self::query_param_matcher).
    pub query_string_parameter_matchers: Option<HashMap<String, Vec<MatcherValue>>>,

    /// Cookie matchers (single value per name). See
    /// [`header_matchers`](Self::header_matchers) and
    /// [`cookie_matcher`](Self::cookie_matcher).
    pub cookie_matchers: Option<HashMap<String, MatcherValue>>,

    /// Guard of a conditional (if/then/else) request matcher (`"if"`). When
    /// set, this request is a conditional matcher: if the guard matches,
    /// [`then_request`](Self::then_request) must match too, otherwise
    /// [`else_request`](Self::else_request) must (with no `else`, it matches
    /// whenever the guard does not). Each branch is an HTTP matcher, an OpenAPI
    /// matcher or another conditional. Build with [`conditional`](Self::conditional).
    pub if_request: Option<Box<HttpRequest>>,

    /// The `"then"` branch of a conditional matcher. See [`if_request`](Self::if_request).
    pub then_request: Option<Box<HttpRequest>>,

    /// The `"else"` branch of a conditional matcher. See [`if_request`](Self::if_request).
    pub else_request: Option<Box<HttpRequest>>,

    /// OpenAPI matcher: a spec URL, file path or classpath resource (a JSON
    /// string), or an inline spec (a JSON string or object) (`"specUrlOrPayload"`).
    /// When set, this request matches against an OpenAPI operation. Build with
    /// [`open_api`](Self::open_api).
    pub spec_url_or_payload: Option<serde_json::Value>,

    /// OpenAPI matcher: the operation to match (`"operationId"`); all
    /// operations when unset.
    pub operation_id: Option<String>,

    /// OpenAPI matcher: a path prefix prepended to the spec's paths
    /// (`"contextPathPrefix"`).
    pub context_path_prefix: Option<String>,

    /// Forward-compatibility catch-all for request fields the typed model does
    /// not yet name (e.g. `clientCertificate`, `localAddress`, `remoteAddress`).
    pub extra: Extra,
}

/// Convert a plain multi-value map to matcher values by parsing each string
/// exactly as the server would, so an existing `"!foo"` keeps meaning "not foo".
/// The meaning is preserved; only the representation changes.
fn plain_multi_as_matchers(
    map: &HashMap<String, Vec<String>>,
) -> HashMap<String, Vec<MatcherValue>> {
    map.iter()
        .map(|(k, vs)| {
            (
                k.clone(),
                vs.iter().map(|v| MatcherValue::from(v.clone())).collect(),
            )
        })
        .collect()
}

/// The effective multi-value map for the wire: the matcher map when it carries
/// anything, otherwise the plain map re-expressed as matcher values (which
/// serialise back to the identical plain strings).
fn effective_multi(
    plain: &Option<HashMap<String, Vec<String>>>,
    matchers: &Option<HashMap<String, Vec<MatcherValue>>>,
) -> Option<HashMap<String, Vec<MatcherValue>>> {
    match matchers {
        Some(m) if !m.is_empty() => Some(m.clone()),
        _ => plain.as_ref().map(plain_multi_as_matchers),
    }
}

/// The effective single-value (cookie) map for the wire. See [`effective_multi`].
fn effective_single(
    plain: &Option<HashMap<String, String>>,
    matchers: &Option<HashMap<String, MatcherValue>>,
) -> Option<HashMap<String, MatcherValue>> {
    match matchers {
        Some(m) if !m.is_empty() => Some(m.clone()),
        _ => plain.as_ref().map(|p| {
            p.iter()
                .map(|(k, v)| (k.clone(), MatcherValue::from(v.clone())))
                .collect()
        }),
    }
}

/// A decoded multi-value field split into (plain map, matcher map) — at most one
/// is `Some`.
type SplitMulti = (
    Option<HashMap<String, Vec<String>>>,
    Option<HashMap<String, Vec<MatcherValue>>>,
);

/// A decoded single-value (cookie) field split into (plain map, matcher map).
type SplitSingle = (
    Option<HashMap<String, String>>,
    Option<HashMap<String, MatcherValue>>,
);

/// Split a decoded multi-value matcher map into the plain map (when every value
/// round-trips through the plain form) or the matcher map (when any value needs
/// the object form). Mirrors the Go client's `decodeMultiValueMatchers`: the
/// choice is per-FIELD, not per-value, because both share one wire key.
fn split_multi(decoded: Option<HashMap<String, Vec<MatcherValue>>>) -> SplitMulti {
    let Some(map) = decoded else {
        return (None, None);
    };
    if map.values().flatten().any(MatcherValue::ambiguous) {
        return (None, Some(map));
    }
    let plain = map
        .into_iter()
        .map(|(k, vs)| (k, vs.iter().map(MatcherValue::serialise).collect()))
        .collect();
    (Some(plain), None)
}

/// [`split_multi`] for a single-value (cookie) field.
fn split_single(decoded: Option<HashMap<String, MatcherValue>>) -> SplitSingle {
    let Some(map) = decoded else {
        return (None, None);
    };
    if map.values().any(MatcherValue::ambiguous) {
        return (None, Some(map));
    }
    let plain = map.into_iter().map(|(k, v)| (k, v.serialise())).collect();
    (Some(plain), None)
}

impl Serialize for HttpRequest {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct Wire<'a> {
            #[serde(skip_serializing_if = "Option::is_none")]
            method: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            path: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            query_string_parameters: Option<HashMap<String, Vec<MatcherValue>>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            headers: Option<HashMap<String, Vec<MatcherValue>>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            body: &'a Option<Body>,
            #[serde(skip_serializing_if = "Option::is_none")]
            jwt: &'a Option<Jwt>,
            #[serde(skip_serializing_if = "Option::is_none")]
            socket_address: &'a Option<SocketAddress>,
            #[serde(skip_serializing_if = "Option::is_none")]
            not: &'a Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            secure: &'a Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            keep_alive: &'a Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            protocol: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            path_parameters: &'a Option<HashMap<String, ParameterValues>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            cookies: Option<HashMap<String, MatcherValue>>,
            #[serde(rename = "if", skip_serializing_if = "Option::is_none")]
            if_request: &'a Option<Box<HttpRequest>>,
            #[serde(rename = "then", skip_serializing_if = "Option::is_none")]
            then_request: &'a Option<Box<HttpRequest>>,
            #[serde(rename = "else", skip_serializing_if = "Option::is_none")]
            else_request: &'a Option<Box<HttpRequest>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            spec_url_or_payload: &'a Option<serde_json::Value>,
            #[serde(skip_serializing_if = "Option::is_none")]
            operation_id: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            context_path_prefix: &'a Option<String>,
            #[serde(flatten)]
            extra: &'a Extra,
        }

        Wire {
            method: &self.method,
            path: &self.path,
            query_string_parameters: effective_multi(
                &self.query_string_parameters,
                &self.query_string_parameter_matchers,
            ),
            headers: effective_multi(&self.headers, &self.header_matchers),
            body: &self.body,
            jwt: &self.jwt,
            socket_address: &self.socket_address,
            not: &self.not,
            secure: &self.secure,
            keep_alive: &self.keep_alive,
            protocol: &self.protocol,
            path_parameters: &self.path_parameters,
            cookies: effective_single(&self.cookies, &self.cookie_matchers),
            if_request: &self.if_request,
            then_request: &self.then_request,
            else_request: &self.else_request,
            spec_url_or_payload: &self.spec_url_or_payload,
            operation_id: &self.operation_id,
            context_path_prefix: &self.context_path_prefix,
            extra: &self.extra,
        }
        .serialize(serializer)
    }
}

impl<'de> Deserialize<'de> for HttpRequest {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Wire {
            #[serde(default, deserialize_with = "de_lenient_optional_string")]
            method: Option<String>,
            #[serde(default, deserialize_with = "de_lenient_optional_string")]
            path: Option<String>,
            #[serde(default)]
            query_string_parameters: Option<HashMap<String, Vec<MatcherValue>>>,
            #[serde(default)]
            headers: Option<HashMap<String, Vec<MatcherValue>>>,
            #[serde(default)]
            body: Option<Body>,
            #[serde(default)]
            jwt: Option<Jwt>,
            #[serde(default)]
            socket_address: Option<SocketAddress>,
            #[serde(default)]
            not: Option<bool>,
            #[serde(default)]
            secure: Option<bool>,
            #[serde(default)]
            keep_alive: Option<bool>,
            #[serde(default)]
            protocol: Option<String>,
            #[serde(default)]
            path_parameters: Option<HashMap<String, ParameterValues>>,
            #[serde(default)]
            cookies: Option<HashMap<String, MatcherValue>>,
            #[serde(default, rename = "if")]
            if_request: Option<Box<HttpRequest>>,
            #[serde(default, rename = "then")]
            then_request: Option<Box<HttpRequest>>,
            #[serde(default, rename = "else")]
            else_request: Option<Box<HttpRequest>>,
            #[serde(default)]
            spec_url_or_payload: Option<serde_json::Value>,
            #[serde(default)]
            operation_id: Option<String>,
            #[serde(default)]
            context_path_prefix: Option<String>,
            #[serde(flatten)]
            extra: Extra,
        }

        let wire = Wire::deserialize(deserializer)?;
        let (headers, header_matchers) = split_multi(wire.headers);
        let (query_string_parameters, query_string_parameter_matchers) =
            split_multi(wire.query_string_parameters);
        let (cookies, cookie_matchers) = split_single(wire.cookies);
        Ok(HttpRequest {
            method: wire.method,
            path: wire.path,
            query_string_parameters,
            headers,
            body: wire.body,
            jwt: wire.jwt,
            socket_address: wire.socket_address,
            not: wire.not,
            secure: wire.secure,
            keep_alive: wire.keep_alive,
            protocol: wire.protocol,
            path_parameters: wire.path_parameters,
            cookies,
            header_matchers,
            query_string_parameter_matchers,
            cookie_matchers,
            if_request: wire.if_request,
            then_request: wire.then_request,
            else_request: wire.else_request,
            spec_url_or_payload: wire.spec_url_or_payload,
            operation_id: wire.operation_id,
            context_path_prefix: wire.context_path_prefix,
            extra: wire.extra,
        })
    }
}

impl HttpRequest {
    /// Create a new empty request matcher.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the downstream socket address to connect to.
    ///
    /// Used by load-scenario steps (and forwarded/proxied requests) to direct
    /// the rendered request at a specific host/port/scheme rather than relying
    /// on the request's `Host` header.
    pub fn socket_address(mut self, socket_address: SocketAddress) -> Self {
        self.socket_address = Some(socket_address);
        self
    }

    /// Set the HTTP method to match.
    pub fn method(mut self, method: impl Into<String>) -> Self {
        self.method = Some(method.into());
        self
    }

    /// Set the path to match.
    pub fn path(mut self, path: impl Into<String>) -> Self {
        self.path = Some(path.into());
        self
    }

    /// Add a query string parameter (multiple values per key supported).
    ///
    /// A leading `!` in the value negates and a leading `?` marks it optional —
    /// the server's marker syntax, and the existing meaning. To match a value
    /// that literally starts with `!` or `?`, use
    /// [`query_param_matcher`](Self::query_param_matcher) with
    /// [`MatcherValue::literal`].
    pub fn query_param(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let params = self
            .query_string_parameters
            .get_or_insert_with(HashMap::new);
        params.entry(key.into()).or_default().push(value.into());
        self
    }

    /// Add a query string parameter matcher whose value is taken verbatim, so a
    /// value starting with `!` or `?` means itself rather than being read as a
    /// negation or optionality marker.
    ///
    /// Use one style per field: once any `query_param_matcher` call is made, the
    /// matcher map replaces the plain [`query_param`](Self::query_param) map for
    /// the whole `queryStringParameters` field on the wire, so a plain
    /// `query_param` call made AFTER this one is ignored.
    pub fn query_param_matcher(mut self, key: impl Into<String>, value: MatcherValue) -> Self {
        if self.query_string_parameter_matchers.is_none() {
            let migrated = self
                .query_string_parameters
                .take()
                .map(|m| plain_multi_as_matchers(&m))
                .unwrap_or_default();
            self.query_string_parameter_matchers = Some(migrated);
        }
        self.query_string_parameter_matchers
            .as_mut()
            .unwrap()
            .entry(key.into())
            .or_default()
            .push(value);
        self
    }

    /// Add a header (multiple values per key supported).
    ///
    /// A leading `!` in the value negates and a leading `?` marks it optional —
    /// the server's marker syntax, and the existing meaning. To match a value
    /// that literally starts with `!` or `?`, use
    /// [`header_matcher`](Self::header_matcher) with [`MatcherValue::literal`].
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers.entry(key.into()).or_default().push(value.into());
        self
    }

    /// Add a header matcher whose value is taken verbatim, so a value starting
    /// with `!` or `?` means itself rather than being read as a negation or
    /// optionality marker.
    ///
    /// Use one style per field: once any `header_matcher` call is made, the
    /// matcher map replaces the plain [`header`](Self::header) map for the whole
    /// `headers` field on the wire, so a plain `header` call made AFTER this one
    /// is ignored.
    pub fn header_matcher(mut self, key: impl Into<String>, value: MatcherValue) -> Self {
        if self.header_matchers.is_none() {
            let migrated = self
                .headers
                .take()
                .map(|m| plain_multi_as_matchers(&m))
                .unwrap_or_default();
            self.header_matchers = Some(migrated);
        }
        self.header_matchers
            .as_mut()
            .unwrap()
            .entry(key.into())
            .or_default()
            .push(value);
        self
    }

    /// Set a plain string body matcher.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = Some(Body::Plain(body.into()));
        self
    }

    /// Set a typed JSON body matcher.
    pub fn json_body(mut self, json: serde_json::Value) -> Self {
        self.body = Some(Body::Typed {
            body_type: "JSON".to_string(),
            json: json.to_string(),
        });
        self
    }

    /// Set a file body (type "FILE") with optional content type and template type.
    ///
    /// Use [`Body::file`] for richer construction if you need content type or
    /// template type set.
    pub fn file_body(mut self, file_path: impl Into<String>) -> Self {
        self.body = Some(Body::File {
            file_path: file_path.into(),
            content_type: None,
            template_type: None,
        });
        self
    }

    /// Set a pre-built [`Body`] value (use with [`Body::file`] for FILE bodies).
    pub fn body_value(mut self, body: Body) -> Self {
        self.body = Some(body);
        self
    }

    /// Make this a binary (non-HTTP) request matcher: a raw message on the
    /// socket matches when its bytes equal `data`. Sent as `binaryData`
    /// (base64) and used with a binary response action such as
    /// [`BinaryResponse`]. A binary matcher must not be combined with the HTTP
    /// fields (method, path, headers, body, …): set only `binary_data` and,
    /// optionally, `not` or `socket_address`.
    pub fn binary_data(mut self, data: impl AsRef<[u8]>) -> Self {
        self.extra.insert(
            "binaryData".into(),
            serde_json::Value::from(BASE64.encode(data.as_ref())),
        );
        self
    }

    /// The decoded `binaryData` bytes of a binary request matcher, or `None`
    /// when this is not one (or its `binaryData` is not valid base64).
    pub fn binary_data_bytes(&self) -> Option<Vec<u8>> {
        self.extra
            .get("binaryData")
            .and_then(|v| v.as_str())
            .and_then(|s| BASE64.decode(s).ok())
    }

    /// Set a JWT request matcher.
    ///
    /// Serialised under the `"jwt"` key alongside `method`/`path`/`headers`.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::{HttpRequest, Jwt};
    ///
    /// let request = HttpRequest::new()
    ///     .method("GET")
    ///     .path("/secure")
    ///     .jwt(
    ///         Jwt::new()
    ///             .claim("sub", "user-123")
    ///             .claim("role", "!admin")
    ///             .issuer("https://issuer.example.com")
    ///             .algorithm("RS256"),
    ///     );
    /// ```
    pub fn jwt(mut self, jwt: Jwt) -> Self {
        self.jwt = Some(jwt);
        self
    }

    /// Negate the whole request matcher.
    pub fn not(mut self, not: bool) -> Self {
        self.not = Some(not);
        self
    }

    /// Match only requests received over TLS.
    pub fn secure(mut self, secure: bool) -> Self {
        self.secure = Some(secure);
        self
    }

    /// Match only keep-alive requests.
    pub fn keep_alive(mut self, keep_alive: bool) -> Self {
        self.keep_alive = Some(keep_alive);
        self
    }

    /// Match the request protocol (e.g. `"HTTP_1_1"`, `"HTTP_2"`).
    pub fn protocol(mut self, protocol: impl Into<String>) -> Self {
        self.protocol = Some(protocol.into());
        self
    }

    /// Match only requests from this client IP address or CIDR range, IPv4 or
    /// IPv6 (e.g. `"10.0.0.0/8"`); prefix with `!` to match any other address
    /// (sent as `clientIp`). It is the TCP peer address, not `X-Forwarded-For`.
    /// Held in [`extra`](Self::extra) so adding it changed no public field.
    pub fn client_ip(mut self, client_ip: impl Into<String>) -> Self {
        self.extra
            .insert("clientIp".into(), serde_json::Value::String(client_ip.into()));
        self
    }

    /// The `clientIp` criterion of this matcher, or `None` when it has none.
    pub fn client_ip_value(&self) -> Option<&str> {
        self.extra.get("clientIp").and_then(|v| v.as_str())
    }

    /// Add a plain path parameter value (multiple values per key supported).
    ///
    /// For schema/nottable matcher forms, set [`path_parameters`](Self::path_parameters)
    /// directly with a [`ParameterValues::Matcher`].
    pub fn path_param(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let params = self.path_parameters.get_or_insert_with(HashMap::new);
        match params
            .entry(key.into())
            .or_insert_with(|| ParameterValues::Values(Vec::new()))
        {
            ParameterValues::Values(v) => v.push(value.into()),
            ParameterValues::Matcher(_) => {
                // Existing entry is a verbatim matcher form; leave it untouched
                // rather than silently coercing it to a plain value.
            }
        }
        self
    }

    /// Add a cookie to match (single value per name).
    ///
    /// A leading `!` in the value negates and a leading `?` marks it optional —
    /// the server's marker syntax, and the existing meaning. To match a value
    /// that literally starts with `!` or `?`, use
    /// [`cookie_matcher`](Self::cookie_matcher) with [`MatcherValue::literal`].
    pub fn cookie(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        let cookies = self.cookies.get_or_insert_with(HashMap::new);
        cookies.insert(name.into(), value.into());
        self
    }

    /// Add a cookie matcher whose value is taken verbatim, so a value starting
    /// with `!` or `?` means itself rather than being read as a negation or
    /// optionality marker.
    ///
    /// Use one style per field: once any `cookie_matcher` call is made, the
    /// matcher map replaces the plain [`cookie`](Self::cookie) map for the whole
    /// `cookies` field on the wire, so a plain `cookie` call made AFTER this one
    /// is ignored.
    pub fn cookie_matcher(mut self, name: impl Into<String>, value: MatcherValue) -> Self {
        if self.cookie_matchers.is_none() {
            let migrated = self
                .cookies
                .take()
                .map(|m| {
                    m.into_iter()
                        .map(|(k, v)| (k, MatcherValue::from(v)))
                        .collect()
                })
                .unwrap_or_default();
            self.cookie_matchers = Some(migrated);
        }
        self.cookie_matchers
            .as_mut()
            .unwrap()
            .insert(name.into(), value);
        self
    }

    /// Add a path parameter matcher whose value is taken verbatim, so a value
    /// starting with `!` or `?` means itself rather than being read as a
    /// negation or optionality marker.
    ///
    /// Path parameters are carried as [`ParameterValues`]; a literal value is
    /// stored in the schema-matcher (object) form so the server reads it exactly
    /// as written. Calling this for a key that already holds plain
    /// [`path_param`](Self::path_param) values REPLACES them with the matcher
    /// form, so use one style per key.
    pub fn path_param_matcher(mut self, key: impl Into<String>, value: MatcherValue) -> Self {
        let element = serde_json::to_value(&value)
            .expect("BUG: MatcherValue::serialize returned Err for an in-memory value");
        let params = self.path_parameters.get_or_insert_with(HashMap::new);
        match params
            .entry(key.into())
            .or_insert_with(|| ParameterValues::Matcher(serde_json::Value::Array(Vec::new())))
        {
            ParameterValues::Matcher(serde_json::Value::Array(values)) => values.push(element),
            slot => {
                *slot = ParameterValues::Matcher(serde_json::Value::Array(vec![element]));
            }
        }
        self
    }

    /// A conditional (if/then/else) request matcher: when `if_request` matches,
    /// `then_request` must match too, otherwise `else_request` must. Serialises
    /// to `{ "if": …, "then": …, "else": … }`. For a matcher with no `else`
    /// branch, use `HttpRequest::new().if_request(..).then_request(..)`.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::HttpRequest;
    ///
    /// let request = HttpRequest::conditional(
    ///     HttpRequest::new().method("POST"),
    ///     HttpRequest::new().path("/orders"),
    ///     HttpRequest::new().method("GET"),
    /// );
    /// assert_eq!(
    ///     serde_json::to_value(&request).unwrap(),
    ///     serde_json::json!({
    ///         "if": { "method": "POST" },
    ///         "then": { "path": "/orders" },
    ///         "else": { "method": "GET" }
    ///     })
    /// );
    /// ```
    pub fn conditional(
        if_request: HttpRequest,
        then_request: HttpRequest,
        else_request: HttpRequest,
    ) -> Self {
        Self::new()
            .if_request(if_request)
            .then_request(then_request)
            .else_request(else_request)
    }

    /// Set the guard (`"if"`) of a conditional matcher.
    pub fn if_request(mut self, request: HttpRequest) -> Self {
        self.if_request = Some(Box::new(request));
        self
    }

    /// Set the `"then"` branch of a conditional matcher.
    pub fn then_request(mut self, request: HttpRequest) -> Self {
        self.then_request = Some(Box::new(request));
        self
    }

    /// Set the `"else"` branch of a conditional matcher.
    pub fn else_request(mut self, request: HttpRequest) -> Self {
        self.else_request = Some(Box::new(request));
        self
    }

    /// A logical OR request matcher: it matches a request when any of
    /// `branches` matches it, trying them in order; with [`not`](Self::not) it
    /// matches when none does. A branch may be an HTTP, OpenAPI, conditional or
    /// anyOf matcher. Serialises to `{ "anyOf": [ … ] }`, held in
    /// [`extra`](Self::extra) so adding it changed no public field.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::HttpRequest;
    ///
    /// let request = HttpRequest::any_of([
    ///     HttpRequest::new().path("/pets"),
    ///     HttpRequest::new().path("/animals"),
    /// ]);
    /// assert_eq!(
    ///     serde_json::to_value(&request).unwrap(),
    ///     serde_json::json!({ "anyOf": [ { "path": "/pets" }, { "path": "/animals" } ] })
    /// );
    /// assert_eq!(request.any_of_value().unwrap().len(), 2);
    /// ```
    pub fn any_of(branches: impl IntoIterator<Item = HttpRequest>) -> Self {
        let branches = branches
            .into_iter()
            .map(|branch| serde_json::to_value(branch).unwrap_or(serde_json::Value::Null))
            .collect();
        let mut request = Self::new();
        request
            .extra
            .insert("anyOf".into(), serde_json::Value::Array(branches));
        request
    }

    /// The branches of this anyOf matcher, or `None` when it is not one.
    pub fn any_of_value(&self) -> Option<Vec<HttpRequest>> {
        serde_json::from_value(self.extra.get("anyOf")?.clone()).ok()
    }

    /// An OpenAPI request matcher for a spec URL, file path, classpath resource
    /// or inline spec — a string, or an inline spec object as a
    /// [`serde_json::Value`].
    pub fn open_api(spec_url_or_payload: impl Into<serde_json::Value>) -> Self {
        Self {
            spec_url_or_payload: Some(spec_url_or_payload.into()),
            ..Self::default()
        }
    }

    /// Set the OpenAPI operation to match (`"operationId"`).
    pub fn operation_id(mut self, operation_id: impl Into<String>) -> Self {
        self.operation_id = Some(operation_id.into());
        self
    }

    /// Set the OpenAPI context path prefix (`"contextPathPrefix"`).
    pub fn context_path_prefix(mut self, context_path_prefix: impl Into<String>) -> Self {
        self.context_path_prefix = Some(context_path_prefix.into());
        self
    }
}

// ---------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------

/// Request/response body — either a plain string, a typed object, or a file reference.
#[derive(Debug, Clone, PartialEq)]
#[non_exhaustive]
pub enum Body {
    /// A plain string body.
    Plain(String),
    /// A typed body (e.g., JSON).
    Typed { body_type: String, json: String },
    /// A file body (`type: "FILE"`), with optional template evaluation.
    File {
        file_path: String,
        content_type: Option<String>,
        template_type: Option<String>,
    },
    /// An `ALL_OF` composite body matcher — every nested body matcher must match.
    ///
    /// Serialises to `{ "type": "ALL_OF", "bodyAllOf": [ <body>, ... ] }`,
    /// recursing through the normal [`Body`] serialisation for each sub-body.
    AllOf(Vec<Body>),
    /// A single-value typed body matcher whose value lives under a named key
    /// (e.g. `JSON_PATH` → `jsonPath`, `REGEX` → `regex`, `XPATH` → `xpath`).
    ///
    /// Serialises to `{ "type": <body_type>, <value_key>: <value> }`. Use the
    /// [`Body::json_path`] / [`Body::regex`] constructors for the common cases.
    Matcher {
        body_type: String,
        value_key: String,
        value: String,
    },
    /// Any typed body object captured verbatim as a JSON object — the
    /// forward-compatible catch-all for body matcher/value types that do not
    /// have a dedicated variant (`STRING`/`subString`, `XML`, `XML_SCHEMA`,
    /// `JSON_SCHEMA`, `PARAMETERS`, `BINARY`, `GRAPHQL`, `MULTIPART`, `WASM`,
    /// `JSON_RPC`, `FUZZY`, …). Serialises the map back exactly, so every body
    /// shape round-trips without silent field loss.
    Object(serde_json::Map<String, serde_json::Value>),
}

impl Body {
    /// Create a FILE body referencing a path on the server filesystem.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::Body;
    ///
    /// let body = Body::file("/data/response.json")
    ///     .with_content_type("application/json")
    ///     .with_template_type("VELOCITY");
    /// ```
    pub fn file(file_path: impl Into<String>) -> Self {
        Body::File {
            file_path: file_path.into(),
            content_type: None,
            template_type: None,
        }
    }

    /// Set the content type (`contentType`) on a body whose type the server
    /// gives a content type: `FILE`, `JSON`, `BINARY`, `STRING` and `XML`.
    ///
    /// An object whose `"type"` is not a MockServer body type (no `"type"`
    /// key, or e.g. GeoJSON's `{"type":"FeatureCollection",…}`) is an inline
    /// JSON payload, so it is wrapped as
    /// `{"type":"JSON","json":<object>,"contentType":…}` and the content type
    /// never becomes part of the payload. Any other body (another body type
    /// such as `PARAMETERS`, [`Body::Plain`], [`Body::AllOf`],
    /// [`Body::Matcher`]) is returned unchanged.
    pub fn with_content_type(self, content_type: impl Into<String>) -> Self {
        match self {
            Body::File {
                file_path,
                template_type,
                ..
            } => Body::File {
                file_path,
                content_type: Some(content_type.into()),
                template_type,
            },
            Body::Typed { body_type, json } => {
                let mut map = serde_json::Map::new();
                map.insert("type".into(), serde_json::Value::from(body_type));
                map.insert("json".into(), serde_json::Value::from(json));
                map.insert(
                    "contentType".into(),
                    serde_json::Value::from(content_type.into()),
                );
                Body::Object(map)
            }
            Body::Object(mut map) => match map.get("type").and_then(|t| t.as_str()) {
                Some("BINARY" | "JSON" | "STRING" | "XML" | "FILE") => {
                    map.insert(
                        "contentType".into(),
                        serde_json::Value::from(content_type.into()),
                    );
                    Body::Object(map)
                }
                Some(t) if SERVER_BODY_TYPES.contains(&t) => Body::Object(map),
                _ => {
                    let mut wrapped = serde_json::Map::new();
                    wrapped.insert("type".into(), serde_json::Value::from("JSON"));
                    wrapped.insert("json".into(), serde_json::Value::Object(map));
                    wrapped.insert(
                        "contentType".into(),
                        serde_json::Value::from(content_type.into()),
                    );
                    Body::Object(wrapped)
                }
            },
            other => other,
        }
    }

    /// Set the template type (e.g., "VELOCITY", "MUSTACHE") on a FILE body.
    /// No-op on other variants.
    pub fn with_template_type(mut self, template_type: impl Into<String>) -> Self {
        match self {
            Body::File {
                template_type: ref mut tt,
                ..
            } => *tt = Some(template_type.into()),
            Body::Object(ref mut map) if is_file_object(map) => {
                map.insert(
                    "templateType".into(),
                    serde_json::Value::from(template_type.into()),
                );
            }
            _ => {}
        }
        self
    }

    /// When `true`, a FILE body's `filePath` is itself rendered as a template
    /// against the request (set a template type too). No-op on other variants.
    ///
    /// [`Body::File`] has no field for this flag, so a templated FILE body is
    /// held as a [`Body::Object`] with `"type": "FILE"`; `false` omits the key.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::Body;
    ///
    /// let body = Body::file("responses/{{ request.pathParameters.name.0 }}.json")
    ///     .with_template_type("MUSTACHE")
    ///     .with_templated_file_path(true);
    /// ```
    pub fn with_templated_file_path(self, templated_file_path: bool) -> Self {
        match self {
            Body::File {
                file_path,
                content_type,
                template_type,
            } if templated_file_path => {
                let mut map = serde_json::Map::new();
                map.insert("type".into(), serde_json::Value::from("FILE"));
                map.insert("filePath".into(), serde_json::Value::from(file_path));
                if let Some(ct) = content_type {
                    map.insert("contentType".into(), serde_json::Value::from(ct));
                }
                if let Some(tt) = template_type {
                    map.insert("templateType".into(), serde_json::Value::from(tt));
                }
                map.insert("templatedFilePath".into(), serde_json::Value::Bool(true));
                Body::Object(map)
            }
            Body::Object(mut map) if is_file_object(&map) => {
                if templated_file_path {
                    map.insert("templatedFilePath".into(), serde_json::Value::Bool(true));
                } else {
                    map.remove("templatedFilePath");
                }
                Body::Object(map)
            }
            other => other,
        }
    }

    /// Create an `ALL_OF` composite body matcher — every nested body matcher
    /// must match for the request body to match.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::Body;
    ///
    /// let body = Body::all_of(vec![
    ///     Body::json_path("$.name"),
    ///     Body::regex(".*active.*"),
    /// ]);
    /// ```
    pub fn all_of(bodies: Vec<Body>) -> Self {
        Body::AllOf(bodies)
    }

    /// Create a `JSON_PATH` body matcher.
    ///
    /// Serialises to `{ "type": "JSON_PATH", "jsonPath": <expression> }`.
    pub fn json_path(expression: impl Into<String>) -> Self {
        Body::Matcher {
            body_type: "JSON_PATH".to_string(),
            value_key: "jsonPath".to_string(),
            value: expression.into(),
        }
    }

    /// Create a `REGEX` body matcher.
    ///
    /// Serialises to `{ "type": "REGEX", "regex": <pattern> }`.
    pub fn regex(pattern: impl Into<String>) -> Self {
        Body::Matcher {
            body_type: "REGEX".to_string(),
            value_key: "regex".to_string(),
            value: pattern.into(),
        }
    }

    /// Create an `XPATH` body matcher (`{ "type": "XPATH", "xpath": <expr> }`).
    pub fn xpath(expression: impl Into<String>) -> Self {
        Body::Matcher {
            body_type: "XPATH".to_string(),
            value_key: "xpath".to_string(),
            value: expression.into(),
        }
    }

    /// Compare the value a `JSON_PATH` body selects, or the text of a node an
    /// `XPATH` body selects, as a date-time: the body then matches only when one
    /// satisfies `date_time`, built with [`MatcherValue::before`],
    /// [`MatcherValue::after`] or [`MatcherValue::equal_to_date_time`].
    ///
    /// Serialises to `{ "type": "JSON_PATH", "jsonPath": <expr>, "dateTime": {...} }`
    /// and is held as a [`Body::Object`].
    ///
    /// ```
    /// use mockserver_client::{Body, MatcherValue};
    ///
    /// let body = Body::json_path("$.order.created").with_date_time(MatcherValue::after("now -1 days"));
    /// ```
    ///
    /// # Panics
    /// Panics if `date_time` is not a date-time matcher or this is not a
    /// `JSON_PATH` or `XPATH` body, so a misplaced comparison is never silently dropped.
    pub fn with_date_time(self, date_time: MatcherValue) -> Self {
        assert!(
            date_time.is_date_time(),
            "with_date_time needs a date-time matcher; build one with MatcherValue::before, after or equal_to_date_time"
        );
        let date_time = serde_json::to_value(&date_time)
            .expect("BUG: MatcherValue serialization failed for an in-memory value");
        let mut map = match self {
            Body::Matcher {
                body_type,
                value_key,
                value,
            } => {
                let mut map = serde_json::Map::new();
                map.insert("type".into(), serde_json::Value::from(body_type));
                map.insert(value_key, serde_json::Value::from(value));
                map
            }
            Body::Object(map) => map,
            other => {
                panic!("with_date_time applies only to a JSON_PATH or XPATH body, not {other:?}")
            }
        };
        match map.get("type").and_then(|t| t.as_str()) {
            Some("JSON_PATH" | "XPATH") => {}
            other => {
                panic!("with_date_time applies only to a JSON_PATH or XPATH body, not {other:?}")
            }
        }
        map.insert("dateTime".into(), date_time);
        Body::Object(map)
    }

    /// The date-time comparison of a `JSON_PATH` or `XPATH` body, if it has one.
    pub fn date_time(&self) -> Option<MatcherValue> {
        match self {
            Body::Object(map) => map
                .get("dateTime")
                .and_then(|value| serde_json::from_value::<MatcherValue>(value.clone()).ok())
                .filter(MatcherValue::is_date_time),
            _ => None,
        }
    }

    /// Create a `STRING` body matcher. When `sub_string` is true the value need
    /// only be a substring of the request body.
    ///
    /// Serialises to `{ "type": "STRING", "string": <value>, "subString": <b> }`.
    pub fn string(value: impl Into<String>, sub_string: bool) -> Self {
        let mut map = serde_json::Map::new();
        map.insert("type".into(), serde_json::Value::from("STRING"));
        map.insert("string".into(), serde_json::Value::from(value.into()));
        map.insert("subString".into(), serde_json::Value::from(sub_string));
        Body::Object(map)
    }

    /// Create an `XML` body matcher (`{ "type": "XML", "xml": <value> }`).
    pub fn xml(value: impl Into<String>) -> Self {
        Self::single_object("XML", "xml", value.into())
    }

    /// Create an `XML_SCHEMA` body matcher
    /// (`{ "type": "XML_SCHEMA", "xmlSchema": <schema> }`).
    pub fn xml_schema(schema: impl Into<String>) -> Self {
        Self::single_object("XML_SCHEMA", "xmlSchema", schema.into())
    }

    /// Create a `JSON_SCHEMA` body matcher
    /// (`{ "type": "JSON_SCHEMA", "jsonSchema": <schema> }`).
    pub fn json_schema(schema: impl Into<String>) -> Self {
        Self::single_object("JSON_SCHEMA", "jsonSchema", schema.into())
    }

    /// Create a `PARAMETERS` (form/body parameter) matcher
    /// (`{ "type": "PARAMETERS", "parameters": { name: [values] } }`).
    pub fn parameters(parameters: HashMap<String, Vec<String>>) -> Self {
        let mut map = serde_json::Map::new();
        map.insert("type".into(), serde_json::Value::from("PARAMETERS"));
        map.insert(
            "parameters".into(),
            serde_json::to_value(parameters).unwrap_or(serde_json::Value::Null),
        );
        Body::Object(map)
    }

    /// Create a `BINARY` body matcher/value from raw bytes (base64-encoded on
    /// the wire as `base64Bytes`), with an optional content type.
    pub fn binary(data: impl AsRef<[u8]>, content_type: Option<String>) -> Self {
        let mut map = serde_json::Map::new();
        map.insert("type".into(), serde_json::Value::from("BINARY"));
        map.insert(
            "base64Bytes".into(),
            serde_json::Value::from(BASE64.encode(data.as_ref())),
        );
        if let Some(ct) = content_type {
            map.insert("contentType".into(), serde_json::Value::from(ct));
        }
        Body::Object(map)
    }

    /// Create a `GRAPHQL` body matcher (`{ "type": "GRAPHQL", "query": <query> }`).
    pub fn graphql(query: impl Into<String>) -> Self {
        let mut map = serde_json::Map::new();
        map.insert("type".into(), serde_json::Value::from("GRAPHQL"));
        map.insert("query".into(), serde_json::Value::from(query.into()));
        Body::Object(map)
    }

    /// Create a `WASM` custom-rule body matcher from a pre-built JSON object.
    ///
    /// Captured verbatim, so any current or future WASM matcher fields survive
    /// a round-trip.
    pub fn wasm(object: serde_json::Map<String, serde_json::Value>) -> Self {
        Body::Object(object)
    }

    /// Build a `Body::Object` from a raw JSON object — the escape hatch for any
    /// body type not covered by a dedicated constructor.
    pub fn object(object: serde_json::Map<String, serde_json::Value>) -> Self {
        Body::Object(object)
    }

    fn single_object(body_type: &str, key: &str, value: String) -> Self {
        let mut map = serde_json::Map::new();
        map.insert("type".into(), serde_json::Value::from(body_type));
        map.insert(key.into(), serde_json::Value::from(value));
        Body::Object(map)
    }
}

/// The body `type` names the server recognises (`org.mockserver.model.Body.Type`).
const SERVER_BODY_TYPES: &[&str] = &[
    "BINARY",
    "JSON",
    "JSON_SCHEMA",
    "JSON_PATH",
    "PARAMETERS",
    "REGEX",
    "FUZZY",
    "STRING",
    "XML",
    "XML_SCHEMA",
    "XPATH",
    "JSON_RPC",
    "GRAPHQL",
    "LOG_EVENT",
    "FILE",
    "WASM",
    "MULTIPART",
    "ALL_OF",
];

impl From<&str> for Body {
    fn from(s: &str) -> Self {
        Body::Plain(s.to_string())
    }
}

impl From<String> for Body {
    fn from(s: String) -> Self {
        Body::Plain(s)
    }
}

impl Serialize for Body {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        match self {
            Body::Plain(s) => serializer.serialize_str(s),
            Body::Typed { body_type, json } => {
                use serde::ser::SerializeMap;
                let mut map = serializer.serialize_map(Some(2))?;
                map.serialize_entry("type", body_type)?;
                map.serialize_entry("json", json)?;
                map.end()
            }
            Body::File {
                file_path,
                content_type,
                template_type,
            } => {
                use serde::ser::SerializeMap;
                let count = 2
                    + content_type.as_ref().map_or(0, |_| 1)
                    + template_type.as_ref().map_or(0, |_| 1);
                let mut map = serializer.serialize_map(Some(count))?;
                map.serialize_entry("type", "FILE")?;
                map.serialize_entry("filePath", file_path)?;
                if let Some(ct) = content_type {
                    map.serialize_entry("contentType", ct)?;
                }
                if let Some(tt) = template_type {
                    map.serialize_entry("templateType", tt)?;
                }
                map.end()
            }
            Body::AllOf(bodies) => {
                use serde::ser::SerializeMap;
                let mut map = serializer.serialize_map(Some(2))?;
                map.serialize_entry("type", "ALL_OF")?;
                map.serialize_entry("bodyAllOf", bodies)?;
                map.end()
            }
            Body::Matcher {
                body_type,
                value_key,
                value,
            } => {
                use serde::ser::SerializeMap;
                let mut map = serializer.serialize_map(Some(2))?;
                map.serialize_entry("type", body_type)?;
                map.serialize_entry(value_key.as_str(), value)?;
                map.end()
            }
            Body::Object(object) => object.serialize(serializer),
        }
    }
}

fn is_file_object(map: &serde_json::Map<String, serde_json::Value>) -> bool {
    map.get("type").and_then(|t| t.as_str()) == Some("FILE")
}

/// Map a single-value matcher `body_type` to its wire value-key and extract the
/// string value from the deserialised object (e.g. `JSON_PATH` → `jsonPath`).
fn matcher_key_value(
    body_type: &str,
    map: &serde_json::Map<String, serde_json::Value>,
) -> Option<(String, String)> {
    let key = match body_type {
        "JSON_PATH" => "jsonPath",
        "REGEX" => "regex",
        "XPATH" => "xpath",
        _ => return None,
    };
    map.get(key)
        .and_then(|v| v.as_str())
        .map(|s| (key.to_string(), s.to_string()))
}

impl<'de> Deserialize<'de> for Body {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        use serde_json::Value;
        let v = Value::deserialize(deserializer)?;
        match v {
            Value::String(s) => Ok(Body::Plain(s)),
            Value::Object(map) => {
                let body_type = map
                    .get("type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("JSON")
                    .to_string();
                let templated_file_path =
                    map.get("templatedFilePath").and_then(|v| v.as_bool()) == Some(true);
                if body_type == "FILE" && templated_file_path {
                    // Body::File has no field for the flag; keep the object verbatim.
                    Ok(Body::Object(map))
                } else if body_type == "FILE" {
                    let file_path = map
                        .get("filePath")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let content_type = map
                        .get("contentType")
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string());
                    let template_type = map
                        .get("templateType")
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string());
                    Ok(Body::File {
                        file_path,
                        content_type,
                        template_type,
                    })
                } else if body_type == "ALL_OF" {
                    let bodies = map
                        .get("bodyAllOf")
                        .and_then(|v| v.as_array())
                        .map(|arr| {
                            arr.iter()
                                .cloned()
                                .map(serde_json::from_value)
                                .collect::<std::result::Result<Vec<Body>, _>>()
                        })
                        .transpose()
                        .map_err(serde::de::Error::custom)?
                        .unwrap_or_default();
                    Ok(Body::AllOf(bodies))
                } else if map.len() == 2 && matcher_key_value(&body_type, &map).is_some() {
                    // Only the bare `{ "type": <T>, <valueKey>: <v> }` shape uses
                    // the dedicated Matcher variant. A matcher carrying extra keys
                    // (not, optional, matchType, contentType, …) falls through to
                    // the verbatim Object variant so those fields are not dropped.
                    let (value_key, value) = matcher_key_value(&body_type, &map)
                        .expect("matcher_key_value checked above");
                    Ok(Body::Matcher {
                        body_type,
                        value_key,
                        value,
                    })
                } else if body_type == "JSON"
                    && map.len() == 2
                    && map.get("json").is_some_and(|v| v.is_string())
                {
                    // Preserve the dedicated typed-JSON representation, but only
                    // for the bare `{ "type": "JSON", "json": ... }` shape — a
                    // JSON body carrying extra keys (matchType, contentType,
                    // not, optional, …) falls through to the verbatim Object
                    // variant so those fields are not dropped.
                    let json = map
                        .get("json")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    Ok(Body::Typed { body_type, json })
                } else {
                    // Any other typed body object (STRING, XML, XML_SCHEMA,
                    // JSON_SCHEMA, PARAMETERS, BINARY, GRAPHQL, MULTIPART, WASM,
                    // JSON_RPC, FUZZY, an inline JSON object literal, …) is kept
                    // verbatim so no field is silently dropped.
                    Ok(Body::Object(map))
                }
            }
            // A JSON array or scalar is a JSON body; keep it typed so that
            // sending it back does not turn it into a STRING body.
            other => Ok(Body::Typed {
                body_type: "JSON".to_string(),
                json: other.to_string(),
            }),
        }
    }
}

// ---------------------------------------------------------------------------
// Jwt
// ---------------------------------------------------------------------------

/// JWT request matcher — matches a JSON Web Token carried on the request.
///
/// Serialised under the request's `"jwt"` key. Each entry in [`claims`](Self::claims)
/// is a claim name mapped to an exact-or-regex string; a leading `!` negates the
/// match. The optional [`issuer`](Self::issuer), [`audience`](Self::audience),
/// [`algorithm`](Self::algorithm), [`header`](Self::header) and
/// [`scheme`](Self::scheme) fields are omitted from the wire form when unset.
///
/// # Example
/// ```
/// use mockserver_client::Jwt;
///
/// let jwt = Jwt::new()
///     .claim("sub", "user-123")
///     .claim("role", "!admin")
///     .claim("email", "^.+@example.com$")
///     .issuer("https://issuer.example.com")
///     .audience("my-api")
///     .algorithm("RS256")
///     .header("authorization")
///     .scheme("Bearer");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Jwt {
    /// Claim name → exact-or-regex value (leading `!` negates).
    pub claims: HashMap<String, String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub issuer: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub audience: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub algorithm: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub header: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,
}

impl Jwt {
    /// Create a new empty JWT matcher (no claims, no constraints).
    pub fn new() -> Self {
        Self::default()
    }

    /// Add a claim constraint. The value is an exact-or-regex string; a leading
    /// `!` negates the match.
    pub fn claim(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.claims.insert(name.into(), value.into());
        self
    }

    /// Replace the full claims map.
    pub fn claims(mut self, claims: HashMap<String, String>) -> Self {
        self.claims = claims;
        self
    }

    /// Require the `iss` (issuer) claim to equal the given value.
    pub fn issuer(mut self, issuer: impl Into<String>) -> Self {
        self.issuer = Some(issuer.into());
        self
    }

    /// Require the `aud` (audience) claim to equal the given value.
    pub fn audience(mut self, audience: impl Into<String>) -> Self {
        self.audience = Some(audience.into());
        self
    }

    /// Require the token to be signed with the given algorithm (e.g. "RS256").
    pub fn algorithm(mut self, algorithm: impl Into<String>) -> Self {
        self.algorithm = Some(algorithm.into());
        self
    }

    /// Set the request header the token is carried in (default "authorization").
    pub fn header(mut self, header: impl Into<String>) -> Self {
        self.header = Some(header.into());
        self
    }

    /// Set the auth scheme prefix stripped from the header value (e.g. "Bearer").
    pub fn scheme(mut self, scheme: impl Into<String>) -> Self {
        self.scheme = Some(scheme.into());
        self
    }
}

// ---------------------------------------------------------------------------
// HttpResponse
// ---------------------------------------------------------------------------

/// Builder for an HTTP response action.
///
/// # Example
/// ```
/// use mockserver_client::HttpResponse;
///
/// let response = HttpResponse::new()
///     .status_code(201)
///     .header("Location", "/api/users/42")
///     .body("{\"id\": 42}");
/// ```
#[derive(Debug, Clone, Default, PartialEq)]
pub struct HttpResponse {
    pub status_code: Option<u16>,

    pub headers: Option<HashMap<String, Vec<String>>>,

    /// A plain string body. Every other body shape (`BINARY`, `JSON`, `FILE`,
    /// `XML`, `STRING` with a content type, an inline JSON object, …) is held
    /// under `extra["body"]` instead; set those with [`body_value`](Self::body_value)
    /// or [`binary_body`](Self::binary_body) and read any body with
    /// [`typed_body`](Self::typed_body). When both are set, this field wins.
    pub body: Option<String>,

    pub delay: Option<Delay>,

    /// Response cookies (single value per name; emitted as `Set-Cookie`).
    pub cookies: Option<HashMap<String, String>>,

    /// HTTP reason phrase (e.g. `"Not Found"`); overrides the default for the
    /// status code.
    pub reason_phrase: Option<String>,

    /// A status-code range to respond with a random status from (e.g. `"2xx"`).
    pub status_code_range: Option<String>,

    /// Response trailers (HTTP/2 trailing headers), multiple values per key.
    pub trailers: Option<HashMap<String, Vec<String>>>,

    /// Generate the response body from an inline/JSON-schema string.
    pub generate_from_schema: Option<String>,

    /// Connection-level options (chunking, content-length, socket close, …).
    pub connection_options: Option<ConnectionOptions>,

    /// Fail the first N requests then recover (circuit-breaker style).
    pub recover_after: Option<RecoverAfter>,

    /// Mark this response as the primary action of a composite expectation.
    pub primary: Option<bool>,

    /// Forward-compatibility catch-all for response fields the typed model does
    /// not yet name, and the home of any non-string `body`.
    pub extra: Extra,
}

/// Serialises an [`Extra`] map, optionally leaving out one key.
struct ExtraWithout<'a> {
    extra: &'a Extra,
    skip: Option<&'static str>,
}

impl Serialize for ExtraWithout<'_> {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.collect_map(
            self.extra
                .iter()
                .filter(|(k, _)| Some(k.as_str()) != self.skip),
        )
    }
}

impl Serialize for HttpResponse {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct Wire<'a> {
            #[serde(skip_serializing_if = "Option::is_none")]
            status_code: &'a Option<u16>,
            #[serde(skip_serializing_if = "Option::is_none")]
            headers: &'a Option<HashMap<String, Vec<String>>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            body: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            delay: &'a Option<Delay>,
            #[serde(skip_serializing_if = "Option::is_none")]
            cookies: &'a Option<HashMap<String, String>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            reason_phrase: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            status_code_range: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            trailers: &'a Option<HashMap<String, Vec<String>>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            generate_from_schema: &'a Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            connection_options: &'a Option<ConnectionOptions>,
            #[serde(skip_serializing_if = "Option::is_none")]
            recover_after: &'a Option<RecoverAfter>,
            #[serde(skip_serializing_if = "Option::is_none")]
            primary: &'a Option<bool>,
            #[serde(flatten)]
            extra: ExtraWithout<'a>,
        }

        Wire {
            status_code: &self.status_code,
            headers: &self.headers,
            body: &self.body,
            delay: &self.delay,
            cookies: &self.cookies,
            reason_phrase: &self.reason_phrase,
            status_code_range: &self.status_code_range,
            trailers: &self.trailers,
            generate_from_schema: &self.generate_from_schema,
            connection_options: &self.connection_options,
            recover_after: &self.recover_after,
            primary: &self.primary,
            // A string `body` and an `extra["body"]` must never both reach the
            // wire as duplicate keys; the typed string field wins.
            extra: ExtraWithout {
                extra: &self.extra,
                skip: self.body.as_ref().map(|_| "body"),
            },
        }
        .serialize(serializer)
    }
}

impl<'de> Deserialize<'de> for HttpResponse {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Wire {
            status_code: Option<u16>,
            headers: Option<HashMap<String, Vec<String>>>,
            #[serde(default)]
            body: Option<serde_json::Value>,
            delay: Option<Delay>,
            cookies: Option<HashMap<String, String>>,
            reason_phrase: Option<String>,
            status_code_range: Option<String>,
            trailers: Option<HashMap<String, Vec<String>>>,
            generate_from_schema: Option<String>,
            connection_options: Option<ConnectionOptions>,
            recover_after: Option<RecoverAfter>,
            primary: Option<bool>,
            #[serde(flatten)]
            extra: Extra,
        }

        let mut wire = Wire::deserialize(deserializer)?;
        let body = match wire.body {
            Some(serde_json::Value::String(s)) => Some(s),
            None | Some(serde_json::Value::Null) => None,
            Some(other) => {
                wire.extra.insert("body".into(), other);
                None
            }
        };
        Ok(HttpResponse {
            status_code: wire.status_code,
            headers: wire.headers,
            body,
            delay: wire.delay,
            cookies: wire.cookies,
            reason_phrase: wire.reason_phrase,
            status_code_range: wire.status_code_range,
            trailers: wire.trailers,
            generate_from_schema: wire.generate_from_schema,
            connection_options: wire.connection_options,
            recover_after: wire.recover_after,
            primary: wire.primary,
            extra: wire.extra,
        })
    }
}

impl HttpResponse {
    /// Create a new empty response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the HTTP status code.
    pub fn status_code(mut self, code: u16) -> Self {
        self.status_code = Some(code);
        self
    }

    /// Add a response header.
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers.entry(key.into()).or_default().push(value.into());
        self
    }

    /// Set the response body as a string.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = Some(body.into());
        self.extra.remove("body");
        self
    }

    /// Set the response body from a [`Body`] value — any shape the server
    /// accepts for a response (`BINARY`, `JSON`, `FILE`, `XML`, `STRING`,
    /// `PARAMETERS`, an inline JSON object via [`Body::object`], …).
    ///
    /// A [`Body::Plain`] goes to the string [`body`](Self::body) field; every
    /// other shape is stored under `extra["body"]`. Either way it replaces any
    /// body set before.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::{Body, HttpResponse};
    ///
    /// let response = HttpResponse::new()
    ///     .body_value(Body::file("/data/order.json").with_content_type("application/json"));
    /// ```
    pub fn body_value(mut self, body: impl Into<Body>) -> Self {
        match body.into() {
            Body::Plain(s) => self.body(s),
            other => {
                self.body = None;
                let value = serde_json::to_value(&other).expect("Body serialises to a JSON value");
                self.extra.insert("body".into(), value);
                self
            }
        }
    }

    /// Set a `BINARY` response body: the raw bytes are base64-encoded on the
    /// wire (`{"type":"BINARY","base64Bytes":…}`) and the server sends them
    /// back unchanged, with `content_type` as the `Content-Type` if given.
    ///
    /// # Example
    /// ```
    /// use mockserver_client::HttpResponse;
    ///
    /// let png_header = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    /// let response = HttpResponse::new()
    ///     .status_code(200)
    ///     .binary_body(png_header, Some("image/png".to_string()));
    /// ```
    pub fn binary_body(self, data: impl AsRef<[u8]>, content_type: Option<String>) -> Self {
        self.body_value(Body::binary(data, content_type))
    }

    /// Set a typed `JSON` response body (`{"type":"JSON","json":…}`).
    pub fn json_body(self, json: serde_json::Value) -> Self {
        self.body_value(Body::Typed {
            body_type: "JSON".to_string(),
            json: json.to_string(),
        })
    }

    /// Set a `FILE` response body read from the server's filesystem. Use
    /// [`body_value`](Self::body_value) with [`Body::file`] to also set a
    /// content type or template type.
    pub fn file_body(self, file_path: impl Into<String>) -> Self {
        self.body_value(Body::file(file_path))
    }

    /// The response body as a [`Body`], whichever form it is held in: the
    /// string [`body`](Self::body) field as [`Body::Plain`], otherwise
    /// `extra["body"]` decoded as a [`Body`]. `None` when no body is set.
    ///
    /// For an expectation read back from the server this reflects the shape the
    /// server returned, which is not always the variant that was sent: for
    /// example a typed JSON body may come back as an inline JSON object
    /// ([`Body::Object`]), and a JSON array as a typed JSON body.
    pub fn typed_body(&self) -> Option<Body> {
        match (&self.body, self.extra.get("body")) {
            (Some(s), _) => Some(Body::Plain(s.clone())),
            (None, Some(v)) => serde_json::from_value(v.clone()).ok(),
            (None, None) => None,
        }
    }

    /// Set a response delay.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Add a response cookie (single value per name).
    pub fn cookie(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        let cookies = self.cookies.get_or_insert_with(HashMap::new);
        cookies.insert(name.into(), value.into());
        self
    }

    /// Set the HTTP reason phrase (e.g. `"Not Found"`).
    pub fn reason_phrase(mut self, reason_phrase: impl Into<String>) -> Self {
        self.reason_phrase = Some(reason_phrase.into());
        self
    }

    /// Set a status-code range to respond with a random status from (e.g. `"2xx"`).
    pub fn status_code_range(mut self, range: impl Into<String>) -> Self {
        self.status_code_range = Some(range.into());
        self
    }

    /// Add a response trailer (HTTP/2 trailing header), multiple values per key.
    pub fn trailer(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let trailers = self.trailers.get_or_insert_with(HashMap::new);
        trailers.entry(key.into()).or_default().push(value.into());
        self
    }

    /// Generate the response body from a schema string.
    pub fn generate_from_schema(mut self, schema: impl Into<String>) -> Self {
        self.generate_from_schema = Some(schema.into());
        self
    }

    /// Set connection-level options.
    pub fn connection_options(mut self, options: ConnectionOptions) -> Self {
        self.connection_options = Some(options);
        self
    }

    /// Set a recover-after (fail-first-N) policy.
    pub fn recover_after(mut self, recover_after: RecoverAfter) -> Self {
        self.recover_after = Some(recover_after);
        self
    }

    /// Mark this response as the primary action of a composite expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpTemplate (response or forward)
// ---------------------------------------------------------------------------

/// Template action — evaluate a response or forward template (Velocity, Mustache, etc.).
///
/// Used as `httpResponseTemplate` or `httpForwardTemplate` in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::HttpTemplate;
///
/// let tmpl = HttpTemplate::new("VELOCITY", "{ \"statusCode\": 200 }")
///     .template_file("/path/to/template.vm");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpTemplate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub template: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_file: Option<String>,

    /// Delay applied before the templated action runs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Response fields applied over the rendered template's output.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_override: Option<HttpResponse>,

    /// The server's `responseModifier` object (headers / cookies add, replace
    /// and remove, a condition, JSON patches), kept as free-form JSON.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_modifier: Option<serde_json::Value>,
}

impl HttpTemplate {
    /// Create a template action with the given type and inline template body.
    pub fn new(template_type: impl Into<String>, template: impl Into<String>) -> Self {
        Self {
            template_type: Some(template_type.into()),
            template: Some(template.into()),
            ..Default::default()
        }
    }

    /// Create a template action that loads from a file path.
    pub fn from_file(template_type: impl Into<String>, file_path: impl Into<String>) -> Self {
        Self {
            template_type: Some(template_type.into()),
            template_file: Some(file_path.into()),
            ..Default::default()
        }
    }

    /// Set the template type (e.g., "VELOCITY", "MUSTACHE").
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }

    /// Set the inline template body.
    pub fn template(mut self, template: impl Into<String>) -> Self {
        self.template = Some(template.into());
        self
    }

    /// Set the template file path (alternative to inline template).
    pub fn template_file(mut self, file_path: impl Into<String>) -> Self {
        self.template_file = Some(file_path.into());
        self
    }

    /// Set a delay applied before the templated action runs.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }

    /// Apply these response fields over the rendered template's output.
    pub fn response_override(mut self, response: HttpResponse) -> Self {
        self.response_override = Some(response);
        self
    }

    /// Rewrite the rendered response with a `responseModifier` object.
    pub fn response_modifier(mut self, modifier: serde_json::Value) -> Self {
        self.response_modifier = Some(modifier);
        self
    }
}

/// [`HttpTemplate`] has no `parameters` field (adding one would break code that
/// builds it as a struct literal), so a template's `parameters` are held in the
/// `extra` map of the struct that owns the template, under `"<wire key>.parameters"`
/// (for example `"httpResponseTemplate.parameters"`). No real field has a `.` in its
/// name. Serializing folds them back into the template object and deserializing lifts
/// them out again, so they survive a deserialize-then-serialize round trip.
fn template_parameters_key(wire_key: &str) -> String {
    format!("{wire_key}.parameters")
}

/// Move each stashed `"<key>.parameters"` from `value`'s top level into `value[<key>]`.
fn fold_template_parameters(value: &mut serde_json::Value, wire_keys: &[&str]) {
    if let Some(object) = value.as_object_mut() {
        for wire_key in wire_keys {
            if let Some(parameters) = object.remove(&template_parameters_key(wire_key)) {
                if let Some(template) = object
                    .get_mut(*wire_key)
                    .and_then(serde_json::Value::as_object_mut)
                {
                    template.insert("parameters".into(), parameters);
                }
            }
        }
    }
}

/// Take `parameters` out of each `value[<key>]`, returning them with their stash key.
fn lift_template_parameters(
    value: &mut serde_json::Value,
    wire_keys: &[&str],
) -> Vec<(String, serde_json::Value)> {
    let mut lifted = Vec::new();
    if let Some(object) = value.as_object_mut() {
        for wire_key in wire_keys {
            if let Some(parameters) = object
                .get_mut(*wire_key)
                .and_then(serde_json::Value::as_object_mut)
                .and_then(|template| template.remove("parameters"))
            {
                lifted.push((template_parameters_key(wire_key), parameters));
            }
        }
    }
    lifted
}

fn has_template_parameters(extra: &Extra, wire_keys: &[&str]) -> bool {
    wire_keys
        .iter()
        .any(|wire_key| extra.contains_key(&template_parameters_key(wire_key)))
}

/// Serialize/Deserialize for a struct whose derived impls are generated as inherent
/// functions (`#[serde(remote = "Self")]`), adding the template-parameter fold and lift.
/// A struct with no stashed parameters serializes exactly as the derived impl would.
macro_rules! serde_with_template_parameters {
    ($ty:ty, [$($wire_key:literal),+]) => {
        impl Serialize for $ty {
            fn serialize<S: serde::Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
                const WIRE_KEYS: &[&str] = &[$($wire_key),+];
                if !has_template_parameters(&self.extra, WIRE_KEYS) {
                    return <$ty>::serialize(self, serializer);
                }
                let mut value = <$ty>::serialize(self, serde_json::value::Serializer).map_err(serde::ser::Error::custom)?;
                fold_template_parameters(&mut value, WIRE_KEYS);
                value.serialize(serializer)
            }
        }

        impl<'de> Deserialize<'de> for $ty {
            fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
                const WIRE_KEYS: &[&str] = &[$($wire_key),+];
                let mut value = serde_json::Value::deserialize(deserializer)?;
                let lifted = lift_template_parameters(&mut value, WIRE_KEYS);
                let mut result = <$ty>::deserialize(value).map_err(serde::de::Error::custom)?;
                for (key, parameters) in lifted {
                    result.extra.insert(key, parameters);
                }
                Ok(result)
            }
        }
    };
}

// ---------------------------------------------------------------------------
// HttpForward
// ---------------------------------------------------------------------------

/// Forward action — proxy the matched request to another host.
///
/// # Example
/// ```
/// use mockserver_client::HttpForward;
///
/// let forward = HttpForward::new("backend.local", 8080);
/// ```
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpForward {
    pub host: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,

    /// Delay applied before the request is forwarded.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpForward {
    /// Create a forward action to the given host and port.
    pub fn new(host: impl Into<String>, port: u16) -> Self {
        Self {
            host: host.into(),
            port: Some(port),
            scheme: None,
            delay: None,
            primary: None,
            extra: Extra::default(),
        }
    }

    /// Set the scheme (HTTP or HTTPS).
    pub fn scheme(mut self, scheme: impl Into<String>) -> Self {
        self.scheme = Some(scheme.into());
        self
    }

    /// Set a delay applied before the request is forwarded.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpClassCallback
// ---------------------------------------------------------------------------

/// Class callback action — delegates the response (or forward) to a server-side
/// class that implements MockServer's callback interface.
///
/// This is a purely declarative (REST-only) callback: no WebSocket is involved.
/// The named class must be on the MockServer server's classpath. Serialized as
/// `httpResponseClassCallback` or `httpForwardClassCallback` in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::HttpClassCallback;
///
/// let cb = HttpClassCallback::new("com.example.MyCallback").primary(true);
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpClassCallback {
    pub callback_class: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,
}

impl HttpClassCallback {
    /// Create a class callback referencing the fully-qualified class name of a
    /// server-side callback implementation.
    pub fn new(callback_class: impl Into<String>) -> Self {
        Self {
            callback_class: callback_class.into(),
            delay: None,
            primary: None,
        }
    }

    /// Set a delay applied before the callback runs.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this callback as primary (kept on the primary event-loop thread).
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpObjectCallback
// ---------------------------------------------------------------------------

/// Object (closure) callback action — delegates the response (or forward) to a
/// client-side closure invoked over the callback WebSocket.
///
/// The `client_id` is the id assigned by MockServer when the client opens the
/// callback WebSocket (`/_mockserver_callback_websocket`). When a request
/// matches, the server pushes it over that socket and the client's registered
/// closure produces the response. Serialized as `httpResponseObjectCallback` or
/// `httpForwardObjectCallback` in an expectation.
///
/// Most users do not construct this directly — use
/// [`MockServerClient::mock_with_callback`](crate::MockServerClient::mock_with_callback),
/// which opens the shared WebSocket, registers the closure, and wires up the
/// `client_id` automatically.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpObjectCallback {
    pub client_id: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_callback: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,
}

impl HttpObjectCallback {
    /// Create an object callback bound to the given callback-WebSocket client id.
    pub fn new(client_id: impl Into<String>) -> Self {
        Self {
            client_id: client_id.into(),
            response_callback: None,
            delay: None,
            primary: None,
        }
    }

    /// Set whether the callback also receives the response (forward + response form).
    pub fn response_callback(mut self, response_callback: bool) -> Self {
        self.response_callback = Some(response_callback);
        self
    }

    /// Set a delay applied before the callback runs.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this callback as primary (kept on the primary event-loop thread).
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpError
// ---------------------------------------------------------------------------

/// Error action — return a connection-level error to the caller.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpError {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drop_connection: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_bytes: Option<String>,

    /// Delay applied before the error is returned.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    /// Reset the matched request stream with this error code (HTTP/2 RST_STREAM /
    /// HTTP/3 RESET_STREAM) instead of returning a response. Takes precedence over
    /// `drop_connection`; HTTP/1.1 has no stream concept.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream_error: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpError {
    /// Create a new error action.
    pub fn new() -> Self {
        Self::default()
    }

    /// Drop the connection without a response.
    pub fn drop_connection(mut self, drop: bool) -> Self {
        self.drop_connection = Some(drop);
        self
    }

    /// Reset the connection with a TCP RST instead of responding, so the client sees
    /// "connection reset by peer" (sent as `resetConnection`). Takes precedence over
    /// `stream_error` and `drop_connection`. Held in [`extra`](Self::extra) so adding it
    /// changed no public field.
    pub fn reset_connection(mut self, reset: bool) -> Self {
        self.extra
            .insert("resetConnection".into(), serde_json::Value::Bool(reset));
        self
    }

    /// The `resetConnection` flag of this error, or `None` when it is not set.
    pub fn reset_connection_value(&self) -> Option<bool> {
        self.extra.get("resetConnection").and_then(|v| v.as_bool())
    }

    /// Send arbitrary bytes then close.
    pub fn response_bytes(mut self, bytes: impl Into<String>) -> Self {
        self.response_bytes = Some(bytes.into());
        self
    }

    /// Set a delay applied before the error is returned.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Reset the matched request stream with this error code instead of responding.
    pub fn stream_error(mut self, code: i64) -> Self {
        self.stream_error = Some(code);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpSseResponse (Server-Sent Events)
// ---------------------------------------------------------------------------

/// A single Server-Sent Event in an [`HttpSseResponse`].
///
/// Maps to the `events[]` entries of the `httpSseResponse` wire shape.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SseEvent {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub event: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry: Option<u32>,

    /// Sent before the other fields as `:` followed by each line exactly as written (`" hb"` sends `: hb`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comment: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,
}

impl SseEvent {
    /// Create a new empty SSE event.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the `event:` field (event type/name).
    pub fn event(mut self, event: impl Into<String>) -> Self {
        self.event = Some(event.into());
        self
    }

    /// Set the `data:` payload.
    pub fn data(mut self, data: impl Into<String>) -> Self {
        self.data = Some(data.into());
        self
    }

    /// Set the `id:` field.
    pub fn id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    /// Set the `retry:` reconnection time in milliseconds.
    pub fn retry(mut self, retry: u32) -> Self {
        self.retry = Some(retry);
        self
    }

    /// Set a comment, sent as `:` + each line before the other fields; an event with only a comment is a heartbeat.
    pub fn comment(mut self, comment: impl Into<String>) -> Self {
        self.comment = Some(comment.into());
        self
    }

    /// Set a delay before this event is emitted.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }
}

/// Builder for a Server-Sent Events (SSE) streaming response action.
///
/// Serialized as the `httpSseResponse` action in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::{HttpSseResponse, SseEvent};
///
/// let sse = HttpSseResponse::new()
///     .status_code(200)
///     .header("Content-Type", "text/event-stream")
///     .event(SseEvent::new().event("message").data("hello").id("1"))
///     .close_connection(true);
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpSseResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_code: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub events: Option<Vec<SseEvent>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_connection: Option<bool>,

    /// The wait after the last event before the stream ends.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_delay: Option<Delay>,

    /// When true the stream stays open after its last event until the client
    /// leaves; `close_delay` is then ignored.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_stream_open: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpSseResponse {
    /// Create a new empty SSE response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the HTTP status code.
    pub fn status_code(mut self, code: u16) -> Self {
        self.status_code = Some(code);
        self
    }

    /// Add a response header.
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers.entry(key.into()).or_default().push(value.into());
        self
    }

    /// Append an SSE event to the stream.
    pub fn event(mut self, event: SseEvent) -> Self {
        self.events.get_or_insert_with(Vec::new).push(event);
        self
    }

    /// Replace all SSE events.
    pub fn events(mut self, events: Vec<SseEvent>) -> Self {
        self.events = Some(events);
        self
    }

    /// Whether to close the connection after emitting all events.
    pub fn close_connection(mut self, close: bool) -> Self {
        self.close_connection = Some(close);
        self
    }

    /// Wait this long after the last event before ending the stream.
    pub fn close_delay(mut self, close_delay: Delay) -> Self {
        self.close_delay = Some(close_delay);
        self
    }

    /// Keep the stream open after its last event until the client leaves.
    pub fn keep_stream_open(mut self, keep_open: bool) -> Self {
        self.keep_stream_open = Some(keep_open);
        self
    }

    /// Set a delay before the response starts.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Set the template type (`VELOCITY`, `JAVASCRIPT` or `MUSTACHE`).
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// GraphqlSubscriptionFilter
// ---------------------------------------------------------------------------

/// GraphQL subscription query filter for the `graphql-transport-ws` protocol.
///
/// When set on an [`HttpWebSocketResponse`], incoming subscribe messages are
/// AST-matched against this filter.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GraphqlSubscriptionFilter {
    pub query: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub operation_name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub variables_schema: Option<String>,

    /// One of `NORMALISED_STRING`, `AST_EXACT`, `AST_SUBSET`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub selection_set_match_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub fields: Option<Vec<String>>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl GraphqlSubscriptionFilter {
    /// Create a filter for the given subscription query.
    pub fn new(query: impl Into<String>) -> Self {
        Self { query: query.into(), ..Default::default() }
    }

    /// Set the operation name.
    pub fn operation_name(mut self, operation_name: impl Into<String>) -> Self {
        self.operation_name = Some(operation_name.into());
        self
    }

    /// Set the variables JSON schema.
    pub fn variables_schema(mut self, variables_schema: impl Into<String>) -> Self {
        self.variables_schema = Some(variables_schema.into());
        self
    }

    /// Set the selection-set match type.
    pub fn selection_set_match_type(mut self, selection_set_match_type: impl Into<String>) -> Self {
        self.selection_set_match_type = Some(selection_set_match_type.into());
        self
    }

    /// Set the fields to match.
    pub fn fields(mut self, fields: Vec<String>) -> Self {
        self.fields = Some(fields);
        self
    }
}
// ---------------------------------------------------------------------------

/// A single WebSocket message in an [`HttpWebSocketResponse`].
///
/// Either `text` or `binary` should be set. Binary data is base64-encoded
/// on the wire (the schema declares `binary` as `format: byte`).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WebSocketMessage {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub binary: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,
}

impl WebSocketMessage {
    /// Create a text WebSocket message.
    pub fn text(text: impl Into<String>) -> Self {
        Self {
            text: Some(text.into()),
            binary: None,
            delay: None,
        }
    }

    /// Create a binary WebSocket message from raw bytes (base64-encoded on the wire).
    pub fn binary(data: impl AsRef<[u8]>) -> Self {
        Self {
            text: None,
            binary: Some(BASE64.encode(data.as_ref())),
            delay: None,
        }
    }

    /// Create a binary WebSocket message from an already base64-encoded string.
    pub fn binary_base64(base64: impl Into<String>) -> Self {
        Self {
            text: None,
            binary: Some(base64.into()),
            delay: None,
        }
    }

    /// Set a delay before this message is sent.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }
}

/// A per-incoming-frame response rule inside an [`HttpWebSocketResponse::matchers`].
///
/// When an incoming WebSocket frame matches this rule (by `frame_type` and/or
/// `text_matcher`), the paired [`responses`](Self::responses) are sent back.
/// Unknown fields are captured in [`extra`](Self::extra) so the shape round-trips
/// without loss.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WebSocketMatcher {
    /// Frame type to match: `"TEXT"`, `"BINARY"`, `"PING"`, `"PONG"` or `"ANY"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frame_type: Option<String>,

    /// Exact-or-regex matcher applied to a text frame's payload.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text_matcher: Option<String>,

    /// Messages sent in reply when an incoming frame matches this rule.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub responses: Option<Vec<WebSocketMessage>>,

    /// Forward-compatibility catch-all for matcher fields not yet named.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl WebSocketMatcher {
    /// Create a new empty matcher rule.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the frame type to match (`"TEXT"`, `"BINARY"`, `"PING"`, `"PONG"`, `"ANY"`).
    pub fn frame_type(mut self, frame_type: impl Into<String>) -> Self {
        self.frame_type = Some(frame_type.into());
        self
    }

    /// Set an exact-or-regex matcher for a text frame's payload.
    pub fn text_matcher(mut self, text_matcher: impl Into<String>) -> Self {
        self.text_matcher = Some(text_matcher.into());
        self
    }

    /// Append a reply message sent when an incoming frame matches this rule.
    pub fn response(mut self, response: WebSocketMessage) -> Self {
        self.responses.get_or_insert_with(Vec::new).push(response);
        self
    }

    /// Replace all reply messages.
    pub fn responses(mut self, responses: Vec<WebSocketMessage>) -> Self {
        self.responses = Some(responses);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpWebSocketResponse
// ---------------------------------------------------------------------------

/// Builder for a WebSocket streaming response action.
///
/// Serialized as the `httpWebSocketResponse` action in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::{HttpWebSocketResponse, WebSocketMessage};
///
/// let ws = HttpWebSocketResponse::new()
///     .subprotocol("chat")
///     .message(WebSocketMessage::text("hello"))
///     .message(WebSocketMessage::binary([0x01, 0x02]))
///     .close_connection(true);
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpWebSocketResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subprotocol: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub messages: Option<Vec<WebSocketMessage>>,

    /// Per-incoming-frame response rules; when set, an incoming frame matching a
    /// rule triggers that rule's `responses`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub matchers: Option<Vec<WebSocketMatcher>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_connection: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    /// GraphQL subscription query filter for the graphql-transport-ws protocol.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub graphql_subscription_filter: Option<GraphqlSubscriptionFilter>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpWebSocketResponse {
    /// Create a new empty WebSocket response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Append an incoming-frame matcher rule.
    pub fn matcher(mut self, matcher: WebSocketMatcher) -> Self {
        self.matchers.get_or_insert_with(Vec::new).push(matcher);
        self
    }

    /// Replace all incoming-frame matcher rules.
    pub fn matchers(mut self, matchers: Vec<WebSocketMatcher>) -> Self {
        self.matchers = Some(matchers);
        self
    }

    /// Set the negotiated subprotocol.
    pub fn subprotocol(mut self, subprotocol: impl Into<String>) -> Self {
        self.subprotocol = Some(subprotocol.into());
        self
    }

    /// Append a WebSocket message to send.
    pub fn message(mut self, message: WebSocketMessage) -> Self {
        self.messages.get_or_insert_with(Vec::new).push(message);
        self
    }

    /// Replace all WebSocket messages.
    pub fn messages(mut self, messages: Vec<WebSocketMessage>) -> Self {
        self.messages = Some(messages);
        self
    }

    /// Whether to close the connection after emitting all messages.
    pub fn close_connection(mut self, close: bool) -> Self {
        self.close_connection = Some(close);
        self
    }

    /// Set a delay before the response starts.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Set the template type (`VELOCITY`, `JAVASCRIPT` or `MUSTACHE`).
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }

    /// Set the GraphQL subscription filter.
    pub fn graphql_subscription_filter(mut self, filter: GraphqlSubscriptionFilter) -> Self {
        self.graphql_subscription_filter = Some(filter);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// DnsResponse
// ---------------------------------------------------------------------------

/// A single DNS resource record in a [`DnsResponse`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DnsRecord {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub record_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub dns_class: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl: Option<u32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<u32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub weight: Option<u32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
}

impl DnsRecord {
    /// Create a new empty DNS record.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create an `A` (IPv4 address) record.
    pub fn a(name: impl Into<String>, ip: impl Into<String>) -> Self {
        Self::new().name(name).record_type("A").value(ip)
    }

    /// Create an `AAAA` (IPv6 address) record.
    pub fn aaaa(name: impl Into<String>, ip: impl Into<String>) -> Self {
        Self::new().name(name).record_type("AAAA").value(ip)
    }

    /// Create a `CNAME` record.
    pub fn cname(name: impl Into<String>, target: impl Into<String>) -> Self {
        Self::new().name(name).record_type("CNAME").value(target)
    }

    /// Create a `TXT` record.
    pub fn txt(name: impl Into<String>, text: impl Into<String>) -> Self {
        Self::new().name(name).record_type("TXT").value(text)
    }

    /// Set the record name.
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }

    /// Set the record type (e.g. "A", "AAAA", "CNAME", "MX", "SRV", "TXT", "PTR").
    pub fn record_type(mut self, record_type: impl Into<String>) -> Self {
        self.record_type = Some(record_type.into());
        self
    }

    /// Set the DNS class (e.g. "IN", "CH", "HS", "ANY").
    pub fn dns_class(mut self, dns_class: impl Into<String>) -> Self {
        self.dns_class = Some(dns_class.into());
        self
    }

    /// Set the time-to-live in seconds.
    pub fn ttl(mut self, ttl: u32) -> Self {
        self.ttl = Some(ttl);
        self
    }

    /// Set the record value (address, target, text, etc.).
    pub fn value(mut self, value: impl Into<String>) -> Self {
        self.value = Some(value.into());
        self
    }

    /// Set the priority (MX/SRV).
    pub fn priority(mut self, priority: u32) -> Self {
        self.priority = Some(priority);
        self
    }

    /// Set the weight (SRV).
    pub fn weight(mut self, weight: u32) -> Self {
        self.weight = Some(weight);
        self
    }

    /// Set the port (SRV).
    pub fn port(mut self, port: u16) -> Self {
        self.port = Some(port);
        self
    }
}

/// Builder for a DNS response action.
///
/// Serialized as the `dnsResponse` action in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::{DnsResponse, DnsRecord};
///
/// let dns = DnsResponse::new()
///     .response_code("NOERROR")
///     .answer_record(DnsRecord::a("example.com", "1.2.3.4").ttl(300));
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DnsResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub answer_records: Option<Vec<DnsRecord>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub authority_records: Option<Vec<DnsRecord>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub additional_records: Option<Vec<DnsRecord>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_code: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl DnsResponse {
    /// Create a new empty DNS response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Append an answer-section record.
    pub fn answer_record(mut self, record: DnsRecord) -> Self {
        self.answer_records
            .get_or_insert_with(Vec::new)
            .push(record);
        self
    }

    /// Replace all answer-section records.
    pub fn answer_records(mut self, records: Vec<DnsRecord>) -> Self {
        self.answer_records = Some(records);
        self
    }

    /// Append an authority-section record.
    pub fn authority_record(mut self, record: DnsRecord) -> Self {
        self.authority_records
            .get_or_insert_with(Vec::new)
            .push(record);
        self
    }

    /// Append an additional-section record.
    pub fn additional_record(mut self, record: DnsRecord) -> Self {
        self.additional_records
            .get_or_insert_with(Vec::new)
            .push(record);
        self
    }

    /// Set the DNS response code (e.g. "NOERROR", "NXDOMAIN", "SERVFAIL").
    pub fn response_code(mut self, code: impl Into<String>) -> Self {
        self.response_code = Some(code.into());
        self
    }

    /// Set a delay before the response is returned.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// BinaryResponse
// ---------------------------------------------------------------------------

/// Builder for a raw binary response action.
///
/// Serialized as the `binaryResponse` action in an expectation. The binary
/// payload is base64-encoded on the wire (the schema declares `binaryData`
/// as a string).
///
/// # Example
/// ```
/// use mockserver_client::BinaryResponse;
///
/// let resp = BinaryResponse::from_bytes([0xDE, 0xAD, 0xBE, 0xEF]);
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BinaryResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub binary_data: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// What happens upstream to the matched message on a relayed connection;
    /// `None` means the server's default, [`BinaryUpstream::AnswerOnly`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub upstream: Option<BinaryUpstream>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

/// What happens upstream to a message a binary expectation matches on a
/// connection MockServer relays to an upstream
/// (`matchBinaryExpectationsWhenProxying`). `AnswerAndForward` and
/// `ForwardAndReplace` need `binaryMessageFraming` `POSTGRESQL`; without it
/// the server answers as `AnswerOnly`.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum BinaryUpstream {
    /// Write the binary data; do not forward the message (the default).
    AnswerOnly,
    /// Write the binary data, forward the message and drop the upstream's reply.
    AnswerAndForward,
    /// Forward the message and write the binary data in place of the upstream's reply.
    ForwardAndReplace,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

impl BinaryResponse {
    /// Create a new empty binary response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a binary response from raw bytes (base64-encoded on the wire).
    pub fn from_bytes(data: impl AsRef<[u8]>) -> Self {
        Self {
            binary_data: Some(BASE64.encode(data.as_ref())),
            delay: None,
            ..Default::default()
        }
    }

    /// Create a binary response from an already base64-encoded string.
    pub fn from_base64(base64: impl Into<String>) -> Self {
        Self {
            binary_data: Some(base64.into()),
            delay: None,
            ..Default::default()
        }
    }

    /// Set the binary payload from raw bytes (base64-encoded on the wire).
    pub fn binary_data(mut self, data: impl AsRef<[u8]>) -> Self {
        self.binary_data = Some(BASE64.encode(data.as_ref()));
        self
    }

    /// Set a delay before the response is returned.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }

    /// Set what happens upstream to the matched message on a relayed connection.
    pub fn upstream(mut self, upstream: BinaryUpstream) -> Self {
        self.upstream = Some(upstream);
        self
    }
}

// ---------------------------------------------------------------------------
// GrpcStreamResponse
// ---------------------------------------------------------------------------

/// A single gRPC stream message in a [`GrpcStreamResponse`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcStreamMessage {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub json: Option<String>,

    /// Template engine used to render the message (`"VELOCITY"`, `"JAVASCRIPT"`
    /// or `"MUSTACHE"`); when unset the `json` is sent verbatim.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,
}

impl GrpcStreamMessage {
    /// Create a gRPC stream message from a JSON-encoded protobuf message string.
    pub fn json(json: impl Into<String>) -> Self {
        Self {
            json: Some(json.into()),
            template_type: None,
            delay: None,
        }
    }

    /// Set the template engine used to render this message.
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }

    /// Set a delay before this message is sent.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }
}

/// Builder for a gRPC streaming response action.
///
/// Serialized as the `grpcStreamResponse` action in an expectation.
///
/// # Example
/// ```
/// use mockserver_client::{GrpcStreamResponse, GrpcStreamMessage};
///
/// let grpc = GrpcStreamResponse::new()
///     .status_name("OK")
///     .message(GrpcStreamMessage::json("{\"id\":1}"))
///     .close_connection(true);
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcStreamResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_message: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub messages: Option<Vec<GrpcStreamMessage>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_connection: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    /// Catch-all for server fields this client does not model yet, so a
    /// retrieve -> re-submit cycle cannot silently destroy them.
    #[serde(flatten, default)]
    pub extra: Extra,
}

impl GrpcStreamResponse {
    /// Create a new empty gRPC stream response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the gRPC status name (e.g. "OK", "NOT_FOUND").
    pub fn status_name(mut self, status_name: impl Into<String>) -> Self {
        self.status_name = Some(status_name.into());
        self
    }

    /// Set the gRPC status message.
    pub fn status_message(mut self, status_message: impl Into<String>) -> Self {
        self.status_message = Some(status_message.into());
        self
    }

    /// Add a response header (gRPC metadata).
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers.entry(key.into()).or_default().push(value.into());
        self
    }

    /// Append a gRPC stream message.
    pub fn message(mut self, message: GrpcStreamMessage) -> Self {
        self.messages.get_or_insert_with(Vec::new).push(message);
        self
    }

    /// Replace all gRPC stream messages.
    pub fn messages(mut self, messages: Vec<GrpcStreamMessage>) -> Self {
        self.messages = Some(messages);
        self
    }

    /// Whether to close the stream after emitting all messages.
    pub fn close_connection(mut self, close: bool) -> Self {
        self.close_connection = Some(close);
        self
    }

    /// Set a delay before the response starts.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }

    /// Mark this action as the primary action of the expectation.
    pub fn primary(mut self, primary: bool) -> Self {
        self.primary = Some(primary);
        self
    }
}

// ---------------------------------------------------------------------------
// OpenApiExpectation
// ---------------------------------------------------------------------------

/// An OpenAPI specification import — registers matchers and example responses
/// for the operations in an OpenAPI/Swagger spec.
///
/// Sent via `PUT /mockserver/openapi`. The spec may be a URL, a filesystem
/// path (`file://...`), a classpath resource, or an inline JSON/YAML payload.
///
/// # Example
/// ```
/// use mockserver_client::OpenApiExpectation;
///
/// let expectation = OpenApiExpectation::new(
///     "https://example.com/petstore.yaml",
/// )
/// .operation("listPets", "200")
/// .operation("showPetById", "200");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct OpenApiExpectation {
    pub spec_url_or_payload: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub operations_and_responses: Option<HashMap<String, String>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub context_path_prefix: Option<String>,
}

impl OpenApiExpectation {
    /// Create an OpenAPI import from a spec URL, file path, classpath resource,
    /// or inline JSON/YAML payload.
    pub fn new(spec_url_or_payload: impl Into<String>) -> Self {
        Self {
            spec_url_or_payload: spec_url_or_payload.into(),
            operations_and_responses: None,
            context_path_prefix: None,
        }
    }

    /// Map an `operationId` to the status code (or example name) to respond with.
    ///
    /// When no operations are specified, MockServer creates example responses
    /// for every operation in the spec.
    pub fn operation(
        mut self,
        operation_id: impl Into<String>,
        status_code: impl Into<String>,
    ) -> Self {
        self.operations_and_responses
            .get_or_insert_with(HashMap::new)
            .insert(operation_id.into(), status_code.into());
        self
    }

    /// Replace the full operations-to-responses map.
    pub fn operations_and_responses(mut self, map: HashMap<String, String>) -> Self {
        self.operations_and_responses = Some(map);
        self
    }

    /// Set a context-path prefix to prepend to every generated matcher path.
    pub fn context_path_prefix(mut self, prefix: impl Into<String>) -> Self {
        self.context_path_prefix = Some(prefix.into());
        self
    }
}

// ---------------------------------------------------------------------------
// Delay
// ---------------------------------------------------------------------------

/// A time delay (e.g., for response delays).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Delay {
    pub time_unit: String,
    pub value: u64,

    /// A variable delay drawn from this distribution instead of `value`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub distribution: Option<DelayDistribution>,

    /// Rendered against the request and read as milliseconds; a non-numeric or
    /// blank result falls back to `time_unit` / `value`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template: Option<String>,

    /// Engine for `template`: `VELOCITY` or `MUSTACHE`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,
}

impl Delay {
    /// Create a delay of `value` in the given time unit (e.g. `"MINUTES"`).
    pub fn new(time_unit: impl Into<String>, value: u64) -> Self {
        Self {
            time_unit: time_unit.into(),
            value,
            distribution: None,
            template: None,
            template_type: None,
        }
    }

    /// Create a delay in milliseconds.
    pub fn milliseconds(value: u64) -> Self {
        Self::new("MILLISECONDS", value)
    }

    /// Create a delay in seconds.
    pub fn seconds(value: u64) -> Self {
        Self::new("SECONDS", value)
    }

    /// Draw the delay from a distribution instead of using `value`.
    pub fn distribution(mut self, distribution: DelayDistribution) -> Self {
        self.distribution = Some(distribution);
        self
    }

    /// Compute the delay in milliseconds from a template rendered against the request.
    pub fn template(mut self, template: impl Into<String>) -> Self {
        self.template = Some(template.into());
        self
    }

    /// Set the engine for the delay template (`VELOCITY` or `MUSTACHE`).
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }
}

/// The distribution of a variable [`Delay`], in milliseconds: `UNIFORM` uses
/// `min` and `max`, `LOG_NORMAL` uses `median` and `p99`, `GAUSSIAN` uses
/// `mean` and `std_dev`.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DelayDistribution {
    #[serde(rename = "type")]
    pub distribution_type: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub min: Option<u64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub max: Option<u64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub median: Option<u64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub p99: Option<u64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub mean: Option<u64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub std_dev: Option<u64>,
}

impl DelayDistribution {
    /// A delay drawn uniformly between `min` and `max` milliseconds.
    pub fn uniform(min: u64, max: u64) -> Self {
        Self { distribution_type: "UNIFORM".to_string(), min: Some(min), max: Some(max), ..Default::default() }
    }

    /// A log-normal delay with the given median and 99th percentile in milliseconds.
    pub fn log_normal(median: u64, p99: u64) -> Self {
        Self { distribution_type: "LOG_NORMAL".to_string(), median: Some(median), p99: Some(p99), ..Default::default() }
    }

    /// A Gaussian delay with the given mean and standard deviation in milliseconds.
    pub fn gaussian(mean: u64, std_dev: u64) -> Self {
        Self { distribution_type: "GAUSSIAN".to_string(), mean: Some(mean), std_dev: Some(std_dev), ..Default::default() }
    }
}

// ---------------------------------------------------------------------------
// Times
// ---------------------------------------------------------------------------

/// How many times an expectation should be matched.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Times {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub remaining_times: Option<u32>,

    #[serde(default)]
    pub unlimited: bool,
}

impl Times {
    /// Match unlimited times.
    pub fn unlimited() -> Self {
        Self {
            remaining_times: None,
            unlimited: true,
        }
    }

    /// Match exactly `n` times.
    pub fn exactly(n: u32) -> Self {
        Self {
            remaining_times: Some(n),
            unlimited: false,
        }
    }

    /// Match once.
    pub fn once() -> Self {
        Self::exactly(1)
    }
}

// ---------------------------------------------------------------------------
// TimeToLive
// ---------------------------------------------------------------------------

/// How long an expectation remains active.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TimeToLive {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_unit: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_live: Option<u64>,

    #[serde(default)]
    pub unlimited: bool,
}

impl TimeToLive {
    /// Unlimited TTL (never expires).
    pub fn unlimited() -> Self {
        Self {
            time_unit: None,
            time_to_live: None,
            unlimited: true,
        }
    }

    /// Expire after the given number of seconds.
    pub fn seconds(seconds: u64) -> Self {
        Self {
            time_unit: Some("SECONDS".to_string()),
            time_to_live: Some(seconds),
            unlimited: false,
        }
    }

    /// Expire after the given number of milliseconds.
    pub fn milliseconds(millis: u64) -> Self {
        Self {
            time_unit: Some("MILLISECONDS".to_string()),
            time_to_live: Some(millis),
            unlimited: false,
        }
    }
}

// ---------------------------------------------------------------------------
// VerificationTimes
// ---------------------------------------------------------------------------

/// Verification constraints — how many times a request must have been received.
///
/// On the wire both `atLeast` and `atMost` are ALWAYS sent, using `-1` to mean
/// "unbounded". The MockServer server deserializes these into primitive `int`
/// fields, so an omitted bound defaults to `0` server-side — which would turn
/// `at_least(n)` into an impossible `between(n, 0)` constraint. Emitting the
/// explicit `-1` sentinel (matching the Java client) avoids that.
#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerificationTimes {
    pub at_least: Option<u32>,
    pub at_most: Option<u32>,
}

impl Serialize for VerificationTimes {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        use serde::ser::SerializeStruct;
        let mut state = serializer.serialize_struct("VerificationTimes", 2)?;
        state.serialize_field("atLeast", &self.at_least.map_or(-1_i64, i64::from))?;
        state.serialize_field("atMost", &self.at_most.map_or(-1_i64, i64::from))?;
        state.end()
    }
}

impl VerificationTimes {
    /// Require at least `n` matching requests.
    pub fn at_least(n: u32) -> Self {
        Self {
            at_least: Some(n),
            at_most: None,
        }
    }

    /// Require at most `n` matching requests.
    pub fn at_most(n: u32) -> Self {
        Self {
            at_least: None,
            at_most: Some(n),
        }
    }

    /// Require exactly `n` matching requests.
    pub fn exactly(n: u32) -> Self {
        Self {
            at_least: Some(n),
            at_most: Some(n),
        }
    }

    /// Require between `min` and `max` matching requests (inclusive).
    pub fn between(min: u32, max: u32) -> Self {
        Self {
            at_least: Some(min),
            at_most: Some(max),
        }
    }
}

// ---------------------------------------------------------------------------
// Stateful scenarios
// ---------------------------------------------------------------------------

/// How MockServer selects which of an expectation's multiple `http_responses`
/// to return on each match. Maps to the `responseMode` field.
///
/// - `Sequential` (default) — cycle through the responses in order.
/// - `Random` — pick a response uniformly at random.
/// - `Weighted` — pick a response weighted by the index-aligned
///   [`Expectation::response_weights`].
/// - `Switch` — return the same response for [`Expectation::switch_after`]
///   matches before advancing to the next.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum ResponseMode {
    /// Cycle through the responses in order (default).
    Sequential,
    /// Pick a response uniformly at random.
    Random,
    /// Pick a response weighted by [`Expectation::response_weights`].
    Weighted,
    /// Return each response for [`Expectation::switch_after`] matches before advancing.
    Switch,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// The protocol event that triggers a [`CrossProtocolScenario`] state
/// transition. Maps to the `trigger` field.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum CrossProtocolTrigger {
    /// A DNS query is observed.
    DnsQuery,
    /// A WebSocket connection is established.
    WebsocketConnect,
    /// A gRPC request is observed.
    GrpcRequest,
    /// An HTTP request is observed.
    HttpRequest,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// A cross-protocol scenario correlation: when a protocol event matching
/// [`trigger`](Self::trigger) (and optionally [`match_pattern`](Self::match_pattern))
/// is observed, the named scenario is advanced to [`target_state`](Self::target_state).
///
/// Maps to entries of the `crossProtocolScenarios` array.
///
/// # Example
/// ```
/// use mockserver_client::{CrossProtocolScenario, CrossProtocolTrigger};
///
/// let scenario = CrossProtocolScenario::new(
///     CrossProtocolTrigger::DnsQuery,
///     "Deploy",
///     "DnsObserved",
/// )
/// .match_pattern("api.example.com");
/// ```
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CrossProtocolScenario {
    pub trigger: CrossProtocolTrigger,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub match_pattern: Option<String>,

    pub scenario_name: String,

    pub target_state: String,
}

impl CrossProtocolScenario {
    /// Create a cross-protocol scenario for the given trigger that advances
    /// `scenario_name` to `target_state` when an event fires.
    pub fn new(
        trigger: CrossProtocolTrigger,
        scenario_name: impl Into<String>,
        target_state: impl Into<String>,
    ) -> Self {
        Self {
            trigger,
            match_pattern: None,
            scenario_name: scenario_name.into(),
            target_state: target_state.into(),
        }
    }

    /// Set the substring filter on the event identifier (omit to match all).
    pub fn match_pattern(mut self, pattern: impl Into<String>) -> Self {
        self.match_pattern = Some(pattern.into());
        self
    }
}

// ---------------------------------------------------------------------------
// ConnectionOptions / RecoverAfter
// ---------------------------------------------------------------------------

/// Connection-level options for an [`HttpResponse`] — control content-length,
/// chunking, keep-alive and socket-close behaviour.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionOptions {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suppress_content_length_header: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub content_length_header_override: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub suppress_connection_header: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub chunk_size: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub chunk_delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_alive_override: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_socket: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_socket_delay: Option<Delay>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl ConnectionOptions {
    /// Create a new empty set of connection options.
    pub fn new() -> Self {
        Self::default()
    }
}

/// Circuit-breaker style policy on an [`HttpResponse`]: fail the first
/// `fail_times` requests with `fail_response`, then serve the real response.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RecoverAfter {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fail_times: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub fail_response: Option<serde_json::Value>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub idempotency_header: Option<String>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl RecoverAfter {
    /// Create a new empty recover-after policy.
    pub fn new() -> Self {
        Self::default()
    }
}

// ---------------------------------------------------------------------------
// RateLimit
// ---------------------------------------------------------------------------

/// Declarative, protocol-agnostic rate limit / quota attached to an expectation.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RateLimit {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    /// `"fixed_window"` (default) or `"token_bucket"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub algorithm: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub limit: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub window_millis: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub burst: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub refill_per_second: Option<f64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_status: Option<i32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after: Option<String>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl RateLimit {
    /// Create a new empty rate limit.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a `fixed_window` rate limit of `limit` requests per `window_millis`.
    pub fn fixed_window(limit: i64, window_millis: i64) -> Self {
        Self {
            algorithm: Some("fixed_window".to_string()),
            limit: Some(limit),
            window_millis: Some(window_millis),
            ..Default::default()
        }
    }

    /// Create a `token_bucket` rate limit with `burst` capacity refilled at
    /// `refill_per_second` tokens per second.
    pub fn token_bucket(burst: i64, refill_per_second: f64) -> Self {
        Self {
            algorithm: Some("token_bucket".to_string()),
            burst: Some(burst),
            refill_per_second: Some(refill_per_second),
            ..Default::default()
        }
    }
}

// ---------------------------------------------------------------------------
// HttpForwardWithFallback / HttpForwardValidateAction / HttpOverrideForwardedRequest
// ---------------------------------------------------------------------------

/// Forward action that falls back to a canned response when the upstream fails
/// (serialised as `httpForwardWithFallback`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpForwardWithFallback {
    pub http_forward: HttpForward,

    pub fallback_response: HttpResponse,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub fallback_on_status_codes: Option<Vec<i32>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub fallback_on_timeout: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpForwardWithFallback {
    /// Create a forward-with-fallback action.
    pub fn new(http_forward: HttpForward, fallback_response: HttpResponse) -> Self {
        Self {
            http_forward,
            fallback_response,
            fallback_on_status_codes: None,
            fallback_on_timeout: None,
            delay: None,
            primary: None,
            extra: Extra::new(),
        }
    }

    /// Fall back when the upstream returns any of these status codes.
    pub fn fallback_on_status_codes(mut self, codes: Vec<i32>) -> Self {
        self.fallback_on_status_codes = Some(codes);
        self
    }

    /// Fall back when the upstream request times out.
    pub fn fallback_on_timeout(mut self, fallback: bool) -> Self {
        self.fallback_on_timeout = Some(fallback);
        self
    }
}

/// Forward action that also validates request/response against an OpenAPI spec
/// (serialised as `httpForwardValidateAction`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpForwardValidateAction {
    pub spec_url_or_payload: String,

    pub host: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub validate_request: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub validate_response: Option<bool>,

    /// `"STRICT"` or `"LOG_ONLY"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub validation_mode: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpForwardValidateAction {
    /// Create a forward-and-validate action against the given spec and host.
    pub fn new(spec_url_or_payload: impl Into<String>, host: impl Into<String>) -> Self {
        Self {
            spec_url_or_payload: spec_url_or_payload.into(),
            host: host.into(),
            port: None,
            scheme: None,
            validate_request: None,
            validate_response: None,
            validation_mode: None,
            delay: None,
            primary: None,
            extra: Extra::new(),
        }
    }
}

/// Override the forwarded request and/or response (serialised as
/// `httpOverrideForwardedRequest`).
///
/// Covers both wire shapes accepted by the server: the modern
/// `requestOverride`/`requestModifier`/`responseOverride`/`responseModifier`
/// form and the legacy `httpRequest`/`httpResponse` form. The `requestModifier`
/// and `responseModifier` sub-objects are kept as free-form JSON
/// ([`serde_json::Value`]) — they round-trip exactly, and the `extra` catch-all
/// preserves any other field.
///
/// # Serialization
///
/// Use `serde_json::to_*` / `from_*` (or any serde format, through the
/// `Serialize` / `Deserialize` traits). Because this type derives with
/// `#[serde(remote = "Self")]` to carry template `parameters`, it also has
/// associated functions named `serialize` and `deserialize`; a path call such as
/// `HttpOverrideForwardedRequest::deserialize(value)` resolves to those raw derived functions, which drop
/// template `parameters` on read and write them at the top level on write. Call
/// `<HttpOverrideForwardedRequest as Deserialize>::deserialize(value)` (or `serde_json::from_value`) instead.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", remote = "Self")]
pub struct HttpOverrideForwardedRequest {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_override: Option<HttpRequest>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_modifier: Option<serde_json::Value>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_override: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_modifier: Option<serde_json::Value>,

    /// The template's `parameters` are held in `extra` under `"responseTemplate.parameters"`,
    /// not in the [`HttpTemplate`]. Assigning this field directly keeps them, so a
    /// new template would get the old parameters: replace a template with
    /// [`HttpOverrideForwardedRequest::response_template_with_parameters`], which sets or clears them with it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_template: Option<HttpTemplate>,

    /// Legacy shape: request to forward.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_request: Option<HttpRequest>,

    /// Legacy shape: response to return.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

serde_with_template_parameters!(HttpOverrideForwardedRequest, ["responseTemplate"]);

impl HttpOverrideForwardedRequest {
    /// Create a new empty override action.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the template that renders the forwarded response, with the `parameters` it
    /// reads (held in `extra` under `"responseTemplate.parameters"`, as for
    /// [`Expectation::respond_template_with_parameters`]). An empty map sets none.
    pub fn response_template_with_parameters(
        mut self,
        template: HttpTemplate,
        parameters: serde_json::Map<String, serde_json::Value>,
    ) -> Self {
        self.response_template = Some(template);
        let key = template_parameters_key("responseTemplate");
        if parameters.is_empty() {
            self.extra.remove(&key);
        } else {
            self.extra
                .insert(key, serde_json::Value::Object(parameters));
        }
        self
    }

    /// The `parameters` of the response template, or `None` when it has none.
    pub fn response_template_parameters(
        &self,
    ) -> Option<&serde_json::Map<String, serde_json::Value>> {
        self.extra
            .get(&template_parameters_key("responseTemplate"))
            .and_then(serde_json::Value::as_object)
    }

    /// Set the request override.
    pub fn request_override(mut self, request: HttpRequest) -> Self {
        self.request_override = Some(request);
        self
    }

    /// Set the response override.
    pub fn response_override(mut self, response: HttpResponse) -> Self {
        self.response_override = Some(response);
        self
    }
}

// ---------------------------------------------------------------------------
// ExpectationAction (before/after) / CaptureRule / ExpectationStep
// ---------------------------------------------------------------------------

/// A side-effect action run before (`beforeActions`) or after (`afterActions`)
/// an expectation's main action fires: an out-of-band request, or a class/object
/// callback.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ExpectationAction {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_request: Option<HttpRequest>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_class_callback: Option<HttpClassCallback>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_object_callback: Option<HttpObjectCallback>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocking: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout: Option<Delay>,

    /// `"FAIL_FAST"` or `"BEST_EFFORT"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_policy: Option<String>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl ExpectationAction {
    /// Create a before/after action that fires an out-of-band HTTP request.
    pub fn request(request: HttpRequest) -> Self {
        Self {
            http_request: Some(request),
            ..Default::default()
        }
    }

    /// Create a before/after action that invokes a server-side class callback.
    pub fn class_callback(callback: HttpClassCallback) -> Self {
        Self {
            http_class_callback: Some(callback),
            ..Default::default()
        }
    }
    /// Create a before/after action whose webhook request `template` renders
    /// from the triggering request (sent as `httpRequestTemplate`). Escape
    /// request values in the template and keep them out of its destination,
    /// or a caller can redirect the webhook.
    pub fn request_template(template: HttpTemplate) -> Self {
        let mut action = Self::default();
        action.extra.insert(
            "httpRequestTemplate".into(),
            serde_json::to_value(template).unwrap_or(serde_json::Value::Null),
        );
        action
    }

    /// The `httpRequestTemplate` of a templated webhook action, or `None` when
    /// this action is not one.
    pub fn http_request_template(&self) -> Option<HttpTemplate> {
        self.extra
            .get("httpRequestTemplate")
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }
}

/// A capture rule (`capture`) — extract a value from the matched request and
/// bind it into scenario/template state under `into`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CaptureRule {
    /// One of `jsonPath`, `xpath`, `header`, `queryStringParameter`, `cookie`,
    /// `pathParameter`.
    pub source: String,

    pub expression: String,

    pub into: String,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl CaptureRule {
    /// Create a capture rule binding `expression` (evaluated against `source`)
    /// into the variable `into`.
    pub fn new(
        source: impl Into<String>,
        expression: impl Into<String>,
        into: impl Into<String>,
    ) -> Self {
        Self {
            source: source.into(),
            expression: expression.into(),
            into: into.into(),
            extra: Extra::new(),
        }
    }
}

/// One step of a multi-step expectation (`steps`) — used to script a sequence of
/// responder/side-effect actions for a single match.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ExpectationStep {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_request: Option<HttpRequest>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_class_callback: Option<HttpClassCallback>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_object_callback: Option<HttpObjectCallback>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward: Option<HttpForward>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_override_forwarded_request: Option<HttpOverrideForwardedRequest>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_error: Option<HttpError>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub responder: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocking: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout: Option<Delay>,

    /// `"FAIL_FAST"` or `"BEST_EFFORT"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_policy: Option<String>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl ExpectationStep {
    /// Create a new empty step.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a step whose responder action is the given response.
    pub fn response(response: HttpResponse) -> Self {
        Self {
            http_response: Some(response),
            responder: Some(true),
            ..Default::default()
        }
    }
}

// ---------------------------------------------------------------------------
// GrpcBidiResponse
// ---------------------------------------------------------------------------

/// A single message in a [`GrpcBidiResponse`] (or one of its rule responses).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcBidiMessage {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub json: Option<String>,

    /// `"VELOCITY"`, `"JAVASCRIPT"` or `"MUSTACHE"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl GrpcBidiMessage {
    /// Create a bidi message from a JSON-encoded protobuf message string.
    pub fn json(json: impl Into<String>) -> Self {
        Self {
            json: Some(json.into()),
            ..Default::default()
        }
    }
}

/// A request-keyed rule in a [`GrpcBidiResponse`] — when an incoming message
/// matches `match_json`, the paired `responses` are sent.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcBidiRule {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub match_json: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub responses: Option<Vec<GrpcBidiMessage>>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

/// A gRPC bidirectional-streaming response action (`grpcBidiResponse`).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcBidiResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_message: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub messages: Option<Vec<GrpcBidiMessage>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub rules: Option<Vec<GrpcBidiRule>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub close_connection: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl GrpcBidiResponse {
    /// Create a new empty gRPC bidi response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Append a streamed message.
    pub fn message(mut self, message: GrpcBidiMessage) -> Self {
        self.messages.get_or_insert_with(Vec::new).push(message);
        self
    }

    /// Append a request-keyed rule.
    pub fn rule(mut self, rule: GrpcBidiRule) -> Self {
        self.rules.get_or_insert_with(Vec::new).push(rule);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpLlmResponse
// ---------------------------------------------------------------------------

/// A single tool call in an [`LlmCompletion`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LlmToolCall {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub arguments: Option<String>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

/// Token-usage accounting for an [`LlmCompletion`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LlmUsage {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_tokens: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_tokens: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub cached_input_tokens: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub cache_creation_tokens: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_tokens: Option<i64>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

/// Streaming timing model (physics) for an [`LlmCompletion`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LlmStreamingPhysics {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_first_token: Option<Delay>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub tokens_per_second: Option<i32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub jitter: Option<f64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub seed: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub subword_streaming: Option<bool>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

/// The chat/text completion of an [`HttpLlmResponse`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LlmCompletion {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<LlmToolCall>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop_reason: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<LlmUsage>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub streaming: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_schema: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub enforce_output_schema: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_choice: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_text: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_signature: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub streaming_physics: Option<LlmStreamingPhysics>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl LlmCompletion {
    /// Create a completion with the given assistant text.
    pub fn text(text: impl Into<String>) -> Self {
        Self {
            text: Some(text.into()),
            ..Default::default()
        }
    }
}

/// The LLM response action (`httpLlmResponse`). Only the completion, embedding,
/// rerank, moderation and content-filter sub-objects are commonly set; each is
/// optional and every unknown/nested field is preserved via its own `extra`
/// catch-all so full LLM configs round-trip.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpLlmResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,

    /// `"ANTHROPIC"`, `"OPENAI"`, `"OPENAI_RESPONSES"`, `"GEMINI"`, `"BEDROCK"`,
    /// `"AZURE_OPENAI"`, `"OLLAMA"`, `"COHERE"`, `"VOYAGE"`, and the OpenAI-chat-compatible
    /// `"MISTRAL"`, `"XAI"`, `"DEEPSEEK"`, `"GROQ"`, `"OPENROUTER"`, `"ORCAROUTER"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub provider: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub completion: Option<LlmCompletion>,

    /// Embedding-response config (kept free-form; round-trips verbatim).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub embedding: Option<serde_json::Value>,

    /// Rerank-response config (kept free-form; round-trips verbatim).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rerank: Option<serde_json::Value>,

    /// Moderation-response config (kept free-form; round-trips verbatim).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub moderation: Option<serde_json::Value>,

    /// Content-filter config (kept free-form; round-trips verbatim).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content_filter: Option<serde_json::Value>,

    /// Conversation-matching predicates (kept free-form; round-trips verbatim).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conversation_predicates: Option<serde_json::Value>,

    /// LLM-specific chaos config (kept free-form; round-trips verbatim).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chaos: Option<serde_json::Value>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<bool>,

    #[serde(flatten, default)]
    pub extra: Extra,
}

impl HttpLlmResponse {
    /// Create a new empty LLM response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the provider (e.g. `"ANTHROPIC"`, `"OPENAI"`).
    pub fn provider(mut self, provider: impl Into<String>) -> Self {
        self.provider = Some(provider.into());
        self
    }

    /// Set the model name.
    pub fn model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    /// Set the completion.
    pub fn completion(mut self, completion: LlmCompletion) -> Self {
        self.completion = Some(completion);
        self
    }
}

// ---------------------------------------------------------------------------
// Expectation
// ---------------------------------------------------------------------------

/// A full expectation combining a request matcher with an action.
///
/// # Serialization
///
/// Use `serde_json::to_*` / `from_*` (or any serde format, through the
/// `Serialize` / `Deserialize` traits). Because this type derives with
/// `#[serde(remote = "Self")]` to carry template `parameters`, it also has
/// associated functions named `serialize` and `deserialize`; a path call such as
/// `Expectation::deserialize(value)` resolves to those raw derived functions, which drop
/// template `parameters` on read and write them at the top level on write. Call
/// `<Expectation as Deserialize>::deserialize(value)` (or `serde_json::from_value`) instead.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", remote = "Self")]
pub struct Expectation {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<i32>,

    /// Match only a percentage (0–100) of otherwise-matching requests.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub percentage: Option<i32>,

    /// Declarative HTTP chaos / fault-injection profile.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chaos: Option<HttpChaosProfile>,

    /// Declarative, protocol-agnostic rate limit / quota.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rate_limit: Option<RateLimit>,

    /// Request matcher. Optional because a `steps`-only (or side-effect-only)
    /// expectation carries no top-level request. Omitted from the wire form when
    /// `None`; an explicitly empty [`HttpRequest`] still serialises as `{}`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub http_request: Option<HttpRequest>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward: Option<HttpForward>,

    /// The template's `parameters` are held in `extra` under `"httpResponseTemplate.parameters"`,
    /// not in the [`HttpTemplate`]. Assigning this field directly keeps them, so a
    /// new template would get the old parameters: replace a template with
    /// [`Expectation::respond_template`], which sets or clears them with it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response_template: Option<HttpTemplate>,

    /// The template's `parameters` are held in `extra` under `"httpForwardTemplate.parameters"`,
    /// not in the [`HttpTemplate`]. Assigning this field directly keeps them, so a
    /// new template would get the old parameters: replace a template with
    /// [`Expectation::forward_template`], which sets or clears them with it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward_template: Option<HttpTemplate>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_error: Option<HttpError>,

    /// Class callback that produces the response (serialized as `httpResponseClassCallback`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response_class_callback: Option<HttpClassCallback>,

    /// Class callback that produces the request to forward (serialized as `httpForwardClassCallback`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward_class_callback: Option<HttpClassCallback>,

    /// Object/closure callback that produces the response (serialized as `httpResponseObjectCallback`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response_object_callback: Option<HttpObjectCallback>,

    /// Object/closure callback that produces the request to forward (serialized as `httpForwardObjectCallback`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward_object_callback: Option<HttpObjectCallback>,

    /// Override the forwarded request/response (`httpOverrideForwardedRequest`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_override_forwarded_request: Option<HttpOverrideForwardedRequest>,

    /// Forward and validate against an OpenAPI spec (`httpForwardValidateAction`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward_validate_action: Option<HttpForwardValidateAction>,

    /// Forward with a fallback response on failure (`httpForwardWithFallback`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward_with_fallback: Option<HttpForwardWithFallback>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_sse_response: Option<HttpSseResponse>,

    /// LLM response action (`httpLlmResponse`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_llm_response: Option<HttpLlmResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_web_socket_response: Option<HttpWebSocketResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub dns_response: Option<DnsResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub binary_response: Option<BinaryResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub grpc_stream_response: Option<GrpcStreamResponse>,

    /// gRPC bidirectional-streaming response action (`grpcBidiResponse`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub grpc_bidi_response: Option<GrpcBidiResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub times: Option<Times>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_live: Option<TimeToLive>,

    /// Name of the state-machine this expectation participates in.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scenario_name: Option<String>,

    /// State the scenario must be in for this expectation to match.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scenario_state: Option<String>,

    /// State the scenario transitions to after this expectation matches.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub new_scenario_state: Option<String>,

    /// Multiple responses; takes priority over the singular [`Expectation::http_response`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_responses: Option<Vec<HttpResponse>>,

    /// How a response is selected from [`Expectation::http_responses`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_mode: Option<ResponseMode>,

    /// Index-aligned relative weights for [`ResponseMode::Weighted`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_weights: Option<Vec<i32>>,

    /// Requests per response block before advancing under [`ResponseMode::Switch`] (default 1).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub switch_after: Option<i32>,

    /// Cross-protocol scenario correlations that advance scenario state on protocol events.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cross_protocol_scenarios: Option<Vec<CrossProtocolScenario>>,

    /// Side-effect actions run before the main action fires (`beforeActions`).
    ///
    /// The server accepts a single object or an array; this client accepts both
    /// on the wire and always serialises an array.
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "one_or_many",
        default
    )]
    pub before_actions: Option<Vec<ExpectationAction>>,

    /// Side-effect actions run after the main action fires (`afterActions`).
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "one_or_many",
        default
    )]
    pub after_actions: Option<Vec<ExpectationAction>>,

    /// Capture rules that bind request values into scenario/template state.
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "one_or_many",
        default
    )]
    pub capture: Option<Vec<CaptureRule>>,

    /// Optional namespace (tenant) this expectation belongs to.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub namespace: Option<String>,

    /// Multi-step script for a single match (`steps`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub steps: Option<Vec<ExpectationStep>>,

    /// Creation timestamp (set by the server; round-tripped when present).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,

    /// Forward-compatibility catch-all for any expectation field the typed model
    /// does not yet name, so unknown fields survive a round-trip instead of
    /// being silently dropped.
    #[serde(flatten, default)]
    pub extra: Extra,
}

serde_with_template_parameters!(Expectation, ["httpResponseTemplate", "httpForwardTemplate"]);

impl Expectation {
    /// Create a new expectation with the given request matcher.
    pub fn new(request: HttpRequest) -> Self {
        Self {
            http_request: Some(request),
            ..Default::default()
        }
    }

    /// Set the expectation ID (for upsert semantics).
    pub fn id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    /// Set the priority (higher = matched first).
    pub fn priority(mut self, priority: i32) -> Self {
        self.priority = Some(priority);
        self
    }

    /// Set a response action.
    pub fn respond(mut self, response: HttpResponse) -> Self {
        self.http_response = Some(response);
        self
    }

    /// Set a forward action.
    pub fn forward(mut self, forward: HttpForward) -> Self {
        self.http_forward = Some(forward);
        self
    }

    /// Set a response template action, replacing any template and its parameters.
    pub fn respond_template(mut self, template: HttpTemplate) -> Self {
        self.http_response_template = Some(template);
        self.extra
            .remove(&template_parameters_key("httpResponseTemplate"));
        self
    }

    /// Set a forward template action, replacing any template and its parameters.
    pub fn forward_template(mut self, template: HttpTemplate) -> Self {
        self.http_forward_template = Some(template);
        self.extra
            .remove(&template_parameters_key("httpForwardTemplate"));
        self
    }

    /// Set a response template action whose template reads `parameters` (such as
    /// `parameters.greeting`), so one template can be reused with different values.
    /// An empty map sets no parameters.
    ///
    /// [`HttpTemplate`] has no `parameters` field, so they are held in this
    /// expectation's `extra` under `"httpResponseTemplate.parameters"` and written
    /// into the template on the wire. A retrieved expectation keeps them the same way,
    /// so it can be upserted again without losing them; read them with
    /// [`Expectation::response_template_parameters`].
    pub fn respond_template_with_parameters(
        self,
        template: HttpTemplate,
        parameters: serde_json::Map<String, serde_json::Value>,
    ) -> Self {
        let mut expectation = self.respond_template(template);
        if !parameters.is_empty() {
            expectation.extra.insert(
                template_parameters_key("httpResponseTemplate"),
                serde_json::Value::Object(parameters),
            );
        }
        expectation
    }

    /// As [`Expectation::respond_template_with_parameters`], for a forward template
    /// (`httpForwardTemplate`).
    pub fn forward_template_with_parameters(
        self,
        template: HttpTemplate,
        parameters: serde_json::Map<String, serde_json::Value>,
    ) -> Self {
        let mut expectation = self.forward_template(template);
        if !parameters.is_empty() {
            expectation.extra.insert(
                template_parameters_key("httpForwardTemplate"),
                serde_json::Value::Object(parameters),
            );
        }
        expectation
    }

    /// The `parameters` of the response template, or `None` when it has none.
    pub fn response_template_parameters(
        &self,
    ) -> Option<&serde_json::Map<String, serde_json::Value>> {
        self.extra
            .get(&template_parameters_key("httpResponseTemplate"))
            .and_then(serde_json::Value::as_object)
    }

    /// The `parameters` of the forward template, or `None` when it has none.
    pub fn forward_template_parameters(
        &self,
    ) -> Option<&serde_json::Map<String, serde_json::Value>> {
        self.extra
            .get(&template_parameters_key("httpForwardTemplate"))
            .and_then(serde_json::Value::as_object)
    }

    /// Set an error action.
    pub fn error(mut self, error: HttpError) -> Self {
        self.http_error = Some(error);
        self
    }

    /// Respond via a server-side class callback (`httpResponseClassCallback`).
    ///
    /// The named class must implement MockServer's callback interface and be on
    /// the server's classpath. Convenience over building an [`HttpClassCallback`]
    /// directly; use [`respond_class_callback`](Self::respond_class_callback) for
    /// the full builder (delay, primary).
    pub fn respond_with_class_callback(mut self, callback_class: impl Into<String>) -> Self {
        self.http_response_class_callback = Some(HttpClassCallback::new(callback_class));
        self
    }

    /// Respond via a pre-built [`HttpClassCallback`] (`httpResponseClassCallback`).
    pub fn respond_class_callback(mut self, callback: HttpClassCallback) -> Self {
        self.http_response_class_callback = Some(callback);
        self
    }

    /// Forward via a server-side class callback (`httpForwardClassCallback`).
    pub fn forward_with_class_callback(mut self, callback_class: impl Into<String>) -> Self {
        self.http_forward_class_callback = Some(HttpClassCallback::new(callback_class));
        self
    }

    /// Forward via a pre-built [`HttpClassCallback`] (`httpForwardClassCallback`).
    pub fn forward_class_callback(mut self, callback: HttpClassCallback) -> Self {
        self.http_forward_class_callback = Some(callback);
        self
    }

    /// Respond via an object/closure callback (`httpResponseObjectCallback`).
    ///
    /// Most users should call
    /// [`MockServerClient::mock_with_callback`](crate::MockServerClient::mock_with_callback)
    /// instead, which opens the callback WebSocket, registers the closure, and
    /// fills in the `client_id` automatically.
    pub fn respond_object_callback(mut self, callback: HttpObjectCallback) -> Self {
        self.http_response_object_callback = Some(callback);
        self
    }

    /// Forward via an object/closure callback (`httpForwardObjectCallback`).
    pub fn forward_object_callback(mut self, callback: HttpObjectCallback) -> Self {
        self.http_forward_object_callback = Some(callback);
        self
    }

    /// Set a Server-Sent Events (SSE) response action.
    pub fn respond_sse(mut self, sse: HttpSseResponse) -> Self {
        self.http_sse_response = Some(sse);
        self
    }

    /// Set a WebSocket response action.
    pub fn respond_web_socket(mut self, ws: HttpWebSocketResponse) -> Self {
        self.http_web_socket_response = Some(ws);
        self
    }

    /// Set a DNS response action.
    pub fn respond_dns(mut self, dns: DnsResponse) -> Self {
        self.dns_response = Some(dns);
        self
    }

    /// Set a raw binary response action.
    pub fn respond_binary(mut self, binary: BinaryResponse) -> Self {
        self.binary_response = Some(binary);
        self
    }

    /// Set a gRPC streaming response action.
    pub fn respond_grpc_stream(mut self, grpc: GrpcStreamResponse) -> Self {
        self.grpc_stream_response = Some(grpc);
        self
    }

    /// Set the number of times this expectation matches.
    pub fn times(mut self, times: Times) -> Self {
        self.times = Some(times);
        self
    }

    /// Set the time-to-live.
    pub fn time_to_live(mut self, ttl: TimeToLive) -> Self {
        self.time_to_live = Some(ttl);
        self
    }

    /// Set the scenario (state-machine) name this expectation participates in.
    pub fn scenario_name(mut self, name: impl Into<String>) -> Self {
        self.scenario_name = Some(name.into());
        self
    }

    /// Set the state the scenario must be in for this expectation to match.
    pub fn scenario_state(mut self, state: impl Into<String>) -> Self {
        self.scenario_state = Some(state.into());
        self
    }

    /// Set the state the scenario transitions to after this expectation matches.
    pub fn new_scenario_state(mut self, state: impl Into<String>) -> Self {
        self.new_scenario_state = Some(state.into());
        self
    }

    /// Append a response to the multiple-responses list (`http_responses`).
    ///
    /// When set, `http_responses` takes priority over the singular
    /// [`respond`](Self::respond) action.
    pub fn respond_with(mut self, response: HttpResponse) -> Self {
        self.http_responses
            .get_or_insert_with(Vec::new)
            .push(response);
        self
    }

    /// Replace all multiple responses (`http_responses`).
    pub fn http_responses(mut self, responses: Vec<HttpResponse>) -> Self {
        self.http_responses = Some(responses);
        self
    }

    /// Set how a response is selected from `http_responses`.
    pub fn response_mode(mut self, mode: ResponseMode) -> Self {
        self.response_mode = Some(mode);
        self
    }

    /// Set the index-aligned relative weights for [`ResponseMode::Weighted`].
    pub fn response_weights(mut self, weights: Vec<i32>) -> Self {
        self.response_weights = Some(weights);
        self
    }

    /// Set the number of requests per response block before advancing under
    /// [`ResponseMode::Switch`].
    pub fn switch_after(mut self, switch_after: i32) -> Self {
        self.switch_after = Some(switch_after);
        self
    }

    /// Append a [`CrossProtocolScenario`] correlation.
    pub fn cross_protocol_scenario(mut self, scenario: CrossProtocolScenario) -> Self {
        self.cross_protocol_scenarios
            .get_or_insert_with(Vec::new)
            .push(scenario);
        self
    }

    /// Replace all cross-protocol scenario correlations.
    pub fn cross_protocol_scenarios(mut self, scenarios: Vec<CrossProtocolScenario>) -> Self {
        self.cross_protocol_scenarios = Some(scenarios);
        self
    }

    /// Match only a percentage (0–100) of otherwise-matching requests.
    pub fn percentage(mut self, percentage: i32) -> Self {
        self.percentage = Some(percentage);
        self
    }

    /// Attach a declarative HTTP chaos / fault-injection profile.
    pub fn chaos(mut self, chaos: HttpChaosProfile) -> Self {
        self.chaos = Some(chaos);
        self
    }

    /// Attach a declarative rate limit / quota.
    pub fn rate_limit(mut self, rate_limit: RateLimit) -> Self {
        self.rate_limit = Some(rate_limit);
        self
    }

    /// Override the forwarded request/response (`httpOverrideForwardedRequest`).
    pub fn override_forwarded_request(
        mut self,
        override_request: HttpOverrideForwardedRequest,
    ) -> Self {
        self.http_override_forwarded_request = Some(override_request);
        self
    }

    /// Forward and validate against an OpenAPI spec (`httpForwardValidateAction`).
    pub fn forward_validate(mut self, action: HttpForwardValidateAction) -> Self {
        self.http_forward_validate_action = Some(action);
        self
    }

    /// Forward with a fallback response on failure (`httpForwardWithFallback`).
    pub fn forward_with_fallback(mut self, action: HttpForwardWithFallback) -> Self {
        self.http_forward_with_fallback = Some(action);
        self
    }

    /// Set an LLM response action (`httpLlmResponse`).
    pub fn respond_llm(mut self, llm: HttpLlmResponse) -> Self {
        self.http_llm_response = Some(llm);
        self
    }

    /// Set a gRPC bidirectional-streaming response action (`grpcBidiResponse`).
    pub fn respond_grpc_bidi(mut self, grpc: GrpcBidiResponse) -> Self {
        self.grpc_bidi_response = Some(grpc);
        self
    }

    /// Append a before-action (`beforeActions`).
    pub fn before_action(mut self, action: ExpectationAction) -> Self {
        self.before_actions
            .get_or_insert_with(Vec::new)
            .push(action);
        self
    }

    /// Append an after-action (`afterActions`).
    pub fn after_action(mut self, action: ExpectationAction) -> Self {
        self.after_actions.get_or_insert_with(Vec::new).push(action);
        self
    }

    /// Append a capture rule (`capture`).
    pub fn capture_rule(mut self, rule: CaptureRule) -> Self {
        self.capture.get_or_insert_with(Vec::new).push(rule);
        self
    }

    /// Set the namespace (tenant) this expectation belongs to.
    pub fn namespace(mut self, namespace: impl Into<String>) -> Self {
        self.namespace = Some(namespace.into());
        self
    }

    /// Append a step to the multi-step script (`steps`).
    pub fn step(mut self, step: ExpectationStep) -> Self {
        self.steps.get_or_insert_with(Vec::new).push(step);
        self
    }

    /// Replace all steps (`steps`).
    pub fn steps(mut self, steps: Vec<ExpectationStep>) -> Self {
        self.steps = Some(steps);
        self
    }

    /// Replace the expectation's `metadata`: free-form labels (owner, ticket,
    /// tags, …) that MockServer stores and returns but never matches requests on.
    /// Values may be any JSON, including `null`. An empty map removes the field.
    ///
    /// Select or clear expectations by metadata with [`ExpectationFilter`].
    pub fn metadata(mut self, metadata: serde_json::Map<String, serde_json::Value>) -> Self {
        if metadata.is_empty() {
            self.extra.remove("metadata");
        } else {
            self.extra
                .insert("metadata".into(), serde_json::Value::Object(metadata));
        }
        self
    }

    /// Add or replace one `metadata` entry, keeping the others. The value converts as
    /// `serde_json` converts it, so a NaN or infinite `f64` is stored as `null`.
    pub fn metadata_entry(
        mut self,
        key: impl Into<String>,
        value: impl Into<serde_json::Value>,
    ) -> Self {
        let mut metadata = self.metadata_map().cloned().unwrap_or_default();
        metadata.insert(key.into(), value.into());
        self.extra
            .insert("metadata".into(), serde_json::Value::Object(metadata));
        self
    }

    /// The expectation's `metadata` object, or `None` when it has none.
    pub fn metadata_map(&self) -> Option<&serde_json::Map<String, serde_json::Value>> {
        self.extra.get("metadata").and_then(serde_json::Value::as_object)
    }
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

/// A verification request sent to MockServer.
///
/// At least one of `http_request` or `http_response` must be set.
/// `http_response` uses the same [`HttpResponse`] type as expectations —
/// the server matches against the recorded response's status code, headers,
/// and body.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Verification {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_request: Option<HttpRequest>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub times: Option<VerificationTimes>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub maximum_number_of_request_to_return_in_verification_failure: Option<u32>,
}

/// A verification sequence request.
///
/// `http_responses` is index-aligned with `http_requests` — each entry
/// constrains the response that must have been returned for the
/// corresponding request.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerificationSequence {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_requests: Option<Vec<HttpRequest>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_responses: Option<Vec<HttpResponse>>,
}

// ---------------------------------------------------------------------------
// Ports
// ---------------------------------------------------------------------------

/// Port list (used by status and bind endpoints).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Ports {
    pub ports: Vec<u16>,
}

// ---------------------------------------------------------------------------
// Scenario state
// ---------------------------------------------------------------------------

/// A scenario and its current state, as returned by the scenario REST
/// endpoints (`GET /mockserver/scenario` and `GET /mockserver/scenario/{name}`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ScenarioState {
    /// The scenario (state-machine) name.
    pub scenario_name: String,
    /// The scenario's current state.
    pub current_state: String,
}

/// Wrapper for the `GET /mockserver/scenario` list response shape
/// (`{"scenarios":[{"scenarioName","currentState"}]}`).
#[derive(Debug, Clone, Deserialize)]
pub(crate) struct ScenarioList {
    #[serde(default)]
    pub scenarios: Vec<ScenarioState>,
}

// ---------------------------------------------------------------------------
// Retrieve types
// ---------------------------------------------------------------------------

/// The type of data to retrieve from MockServer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum RetrieveType {
    /// Recorded inbound requests.
    Requests,
    /// Active (live) expectations.
    ActiveExpectations,
    /// Recorded expectations (from proxy mode).
    RecordedExpectations,
    /// Log messages.
    Logs,
    /// Request/response pairs.
    RequestResponses,
}

impl RetrieveType {
    /// The query parameter value for this type.
    pub fn as_str(&self) -> &'static str {
        match self {
            RetrieveType::Requests => "REQUESTS",
            RetrieveType::ActiveExpectations => "ACTIVE_EXPECTATIONS",
            RetrieveType::RecordedExpectations => "RECORDED_EXPECTATIONS",
            RetrieveType::Logs => "LOGS",
            RetrieveType::RequestResponses => "REQUEST_RESPONSES",
        }
    }
}

/// The response format for retrieve calls.
///
/// In addition to JSON and log-entry formats, MockServer can return the
/// retrieved expectations as SDK setup code (the builder code that recreates
/// the expectations) in a range of languages.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum RetrieveFormat {
    Json,
    LogEntries,
    Java,
    JavaScript,
    Python,
    Go,
    CSharp,
    Ruby,
    Rust,
    Php,
}

impl RetrieveFormat {
    /// The query parameter value for this format.
    pub fn as_str(&self) -> &'static str {
        match self {
            RetrieveFormat::Json => "JSON",
            RetrieveFormat::LogEntries => "LOG_ENTRIES",
            RetrieveFormat::Java => "JAVA",
            RetrieveFormat::JavaScript => "JAVASCRIPT",
            RetrieveFormat::Python => "PYTHON",
            RetrieveFormat::Go => "GO",
            RetrieveFormat::CSharp => "CSHARP",
            RetrieveFormat::Ruby => "RUBY",
            RetrieveFormat::Rust => "RUST",
            RetrieveFormat::Php => "PHP",
        }
    }
}

/// The type of data to clear from MockServer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum ClearType {
    All,
    Log,
    Expectations,
}

impl ClearType {
    /// The query parameter value for this type.
    pub fn as_str(&self) -> &'static str {
        match self {
            ClearType::All => "ALL",
            ClearType::Log => "LOG",
            ClearType::Expectations => "EXPECTATIONS",
        }
    }
}

// ---------------------------------------------------------------------------
// Expectation filter (metadata, unmatched, paging)
// ---------------------------------------------------------------------------

/// A metadata value to select on. A string is sent as its text; any other value
/// as its compact JSON (`42`, `true`, `["a","b"]`, `null`).
///
/// Deliberately not implemented for `Option`, so `None` cannot be mistaken for
/// "any value": use [`ExpectationFilter::metadata_key`] for that, or
/// `serde_json::Value::Null` to select a JSON `null`.
pub trait IntoMetadataValue {
    /// The value as JSON, or why it has none. A NaN or infinite `f64` has no JSON form;
    /// `serde_json` would turn it into `null`, which would select a different expectation.
    fn into_metadata_value(self) -> Result<serde_json::Value, String>;
}

impl IntoMetadataValue for serde_json::Value {
    fn into_metadata_value(self) -> Result<serde_json::Value, String> {
        Ok(self)
    }
}

impl IntoMetadataValue for serde_json::Map<String, serde_json::Value> {
    fn into_metadata_value(self) -> Result<serde_json::Value, String> {
        Ok(serde_json::Value::Object(self))
    }
}

impl<T: IntoMetadataValue> IntoMetadataValue for Vec<T> {
    fn into_metadata_value(self) -> Result<serde_json::Value, String> {
        self.into_iter()
            .map(IntoMetadataValue::into_metadata_value)
            .collect::<Result<Vec<_>, _>>()
            .map(serde_json::Value::Array)
    }
}

macro_rules! into_metadata_value_via_json {
    ($($t:ty),*) => {$(
        impl IntoMetadataValue for $t {
            fn into_metadata_value(self) -> Result<serde_json::Value, String> {
                Ok(serde_json::Value::from(self))
            }
        }
    )*};
}

into_metadata_value_via_json!(&str, String, bool, i32, i64, u32, u64);

impl IntoMetadataValue for f64 {
    fn into_metadata_value(self) -> Result<serde_json::Value, String> {
        serde_json::Number::from_f64(self)
            .map(serde_json::Value::Number)
            .ok_or_else(|| format!("the metadata value {self} is not a finite number"))
    }
}

impl IntoMetadataValue for &String {
    fn into_metadata_value(self) -> Result<serde_json::Value, String> {
        Ok(serde_json::Value::String(self.clone()))
    }
}

/// Selects expectations by metadata or by whether they have ever matched a
/// request, and pages the retrieved list. Sent as query parameters on
/// `PUT /mockserver/retrieve` and `PUT /mockserver/clear`.
///
/// Every selection that is set must hold. Blank keys and JSONPaths are rejected
/// before anything is sent, and a clear must select something and must not
/// page, so a mistaken filter can never widen into "clear every expectation".
///
/// ```
/// use mockserver_client::ExpectationFilter;
///
/// let filter = ExpectationFilter::new().metadata("owner", "team-a").limit(10);
/// ```
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ExpectationFilter {
    metadata_key: Option<String>,
    metadata_value: Option<String>,
    metadata_json_path: Option<String>,
    unmatched: bool,
    offset: Option<u32>,
    limit: Option<u32>,
    invalid: Option<String>,
}

impl ExpectationFilter {
    /// An empty filter.
    pub fn new() -> Self {
        Self::default()
    }

    /// Select expectations whose metadata has `key` with a value equal to
    /// `value` (see [`IntoMetadataValue`] for how the value is sent).
    pub fn metadata(mut self, key: impl Into<String>, value: impl IntoMetadataValue) -> Self {
        self.metadata_key = Some(key.into());
        match value.into_metadata_value() {
            Ok(serde_json::Value::String(text)) => self.metadata_value = Some(text),
            Ok(other) => self.metadata_value = Some(other.to_string()),
            // kept and returned by query_pairs, so nothing is sent
            Err(reason) => self.invalid = Some(reason),
        }
        self
    }

    /// Select expectations whose metadata has `key`, whatever its value.
    pub fn metadata_key(mut self, key: impl Into<String>) -> Self {
        self.metadata_key = Some(key.into());
        self.metadata_value = None;
        self
    }

    /// Select expectations whose metadata object the JSONPath selects at least
    /// one value from, for example `$[?(@.owner == 'team-a')]`.
    pub fn metadata_json_path(mut self, json_path: impl Into<String>) -> Self {
        self.metadata_json_path = Some(json_path.into());
        self
    }

    /// Select only expectations that have not matched any request.
    pub fn unmatched(mut self, unmatched: bool) -> Self {
        self.unmatched = unmatched;
        self
    }

    /// Skip this many expectations (retrieve only).
    pub fn offset(mut self, offset: u32) -> Self {
        self.offset = Some(offset);
        self
    }

    /// Return at most this many expectations (retrieve only).
    pub fn limit(mut self, limit: u32) -> Self {
        self.limit = Some(limit);
        self
    }

    /// `true` when the filter selects a subset (metadata or unmatched).
    pub fn has_selection(&self) -> bool {
        self.metadata_key.is_some() || self.metadata_json_path.is_some() || self.unmatched
    }

    /// `true` when the filter pages the result.
    pub fn has_paging(&self) -> bool {
        self.offset.is_some() || self.limit.is_some()
    }

    /// Validate the filter and return its query parameters, in the order the
    /// other MockServer clients send them. Values are raw; the caller encodes them.
    pub(crate) fn query_pairs(&self) -> Result<Vec<(&'static str, String)>, String> {
        if let Some(reason) = &self.invalid {
            return Err(reason.clone());
        }
        let mut pairs = Vec::new();
        if let Some(key) = &self.metadata_key {
            if key.trim().is_empty() {
                return Err("the metadata key must not be blank".into());
            }
            pairs.push(("metadataKey", key.clone()));
            if let Some(value) = &self.metadata_value {
                pairs.push(("metadataValue", value.clone()));
            }
        }
        if let Some(path) = &self.metadata_json_path {
            if path.trim().is_empty() {
                return Err("the metadata JSONPath must not be blank".into());
            }
            pairs.push(("metadataJsonPath", path.clone()));
        }
        if self.unmatched {
            pairs.push(("unmatched", "true".into()));
        }
        for (name, value) in [("limit", self.limit), ("offset", self.offset)] {
            if let Some(value) = value {
                if value > i32::MAX as u32 {
                    return Err(format!("{name} must not be greater than {}", i32::MAX));
                }
                pairs.push((name, value.to_string()));
            }
        }
        Ok(pairs)
    }
}

/// Options that shape the expectations returned by
/// [`MockServerClient::retrieve_recorded_expectations_with_options`](crate::MockServerClient::retrieve_recorded_expectations_with_options).
///
/// Sent as query parameters on `PUT /mockserver/retrieve?type=RECORDED_EXPECTATIONS`.
/// Each option is off unless set. An invalid option is kept and reported by the
/// client method it is passed to, so nothing is sent.
///
/// ```
/// use mockserver_client::RecordedExpectationsOptions;
///
/// let options = RecordedExpectationsOptions::new()
///     .repeats_as_scenarios(true)
///     .capture_headers(["X-Tenant"])
///     .extract_bodies_larger_than(1024);
/// ```
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RecordedExpectationsOptions {
    consolidate: bool,
    parameterize: bool,
    repeats_as_scenarios: bool,
    capture_headers: Option<Vec<String>>,
    extract_bodies_larger_than: Option<u64>,
}

impl RecordedExpectationsOptions {
    /// Options with every option off.
    pub fn new() -> Self {
        Self::default()
    }

    /// Collapse recorded exchanges that share a request shape into one
    /// reusable, unlimited-times expectation.
    pub fn consolidate(mut self, consolidate: bool) -> Self {
        self.consolidate = consolidate;
        self
    }

    /// Also generalise volatile path, query, header and body values into
    /// matchers (implies consolidate).
    pub fn parameterize(mut self, parameterize: bool) -> Self {
        self.parameterize = parameterize;
        self
    }

    /// Replay a request recorded several times with different responses in the
    /// recorded order, then keep returning the last one.
    pub fn repeats_as_scenarios(mut self, repeats_as_scenarios: bool) -> Self {
        self.repeats_as_scenarios = repeats_as_scenarios;
        self
    }

    /// Keep only these request headers (case-insensitive) in each recorded
    /// matcher; an empty list keeps none.
    pub fn capture_headers<I, S>(mut self, header_names: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.capture_headers = Some(header_names.into_iter().map(Into::into).collect());
        self
    }

    /// Write each response body larger than this many bytes to the server's
    /// `recordedBodyFilesDirectory` and return a `FILE` body that points at it.
    pub fn extract_bodies_larger_than(mut self, threshold_bytes: u64) -> Self {
        self.extract_bodies_larger_than = Some(threshold_bytes);
        self
    }

    /// Validate the options and return their query parameters. Values are raw;
    /// the caller encodes them.
    pub(crate) fn query_pairs(&self) -> Result<Vec<(&'static str, String)>, String> {
        let mut pairs = Vec::new();
        for (name, on) in [
            ("consolidate", self.consolidate),
            ("parameterize", self.parameterize),
            ("repeatsAsScenarios", self.repeats_as_scenarios),
        ] {
            if on {
                pairs.push((name, "true".to_string()));
            }
        }
        if let Some(names) = &self.capture_headers {
            if let Some(bad) = names
                .iter()
                .find(|n| n.trim().is_empty() || n.contains(','))
            {
                return Err(format!(
                    "each capture header must be a non-blank header name without a comma, got {bad:?}"
                ));
            }
            pairs.push(("captureHeaders", names.join(",")));
        }
        if let Some(threshold) = self.extract_bodies_larger_than {
            if threshold > i64::MAX as u64 {
                return Err(format!(
                    "extractBodiesLargerThan must not be greater than {}",
                    i64::MAX
                ));
            }
            pairs.push(("extractBodiesLargerThan", threshold.to_string()));
        }
        Ok(pairs)
    }
}

// ---------------------------------------------------------------------------
// Pact verification result
// ---------------------------------------------------------------------------

/// Outcome of a Pact contract verification (`PUT /mockserver/pact/verify`).
///
/// The server replies `202 ACCEPTED` when every interaction in the contract
/// matched an active expectation, or `406 NOT_ACCEPTABLE` when verification
/// failed — in both cases the body is the same verification report JSON.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PactVerification {
    /// `true` when verification passed (`202`), `false` when it failed (`406`).
    pub passed: bool,
    /// The verification report JSON returned by the server (verbatim).
    pub report: String,
}

// ---------------------------------------------------------------------------
// Operating mode
// ---------------------------------------------------------------------------

/// High-level operating mode for MockServer (set via `PUT /mockserver/mode`,
/// read via `GET /mockserver/mode`).
///
/// Each mode packages the common record / replay / pass-through workflows into a
/// single switch (a convenience over `attemptToProxyIfNoMatchingExpectation`):
///
/// * [`MockMode::Simulate`] — match expectations and return mocks; unmatched
///   requests get a `404`. This is the default (proxy-on-no-match disabled).
/// * [`MockMode::Spy`] — match expectations and return mocks, but forward
///   unmatched requests to the real upstream so they are served live and recorded
///   (proxy-on-no-match enabled).
/// * [`MockMode::Capture`] — forward and record; with no expectations defined this
///   captures all traffic. Backed by the same proxy flag as [`MockMode::Spy`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum MockMode {
    /// Match expectations; unmatched requests get a `404` (default).
    Simulate,
    /// Match expectations; unmatched requests forwarded to the upstream and recorded.
    Spy,
    /// Forward and record all traffic.
    Capture,
}

impl MockMode {
    /// The wire value for this mode (the `mode` query parameter / JSON field).
    pub fn as_str(&self) -> &'static str {
        match self {
            MockMode::Simulate => "SIMULATE",
            MockMode::Spy => "SPY",
            MockMode::Capture => "CAPTURE",
        }
    }

    /// Whether, in this mode, a request matching no expectation is proxied to its
    /// upstream (and thereby recorded) rather than answered with a `404`.
    pub fn proxy_unmatched_requests(&self) -> bool {
        !matches!(self, MockMode::Simulate)
    }
}

impl std::fmt::Display for MockMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl std::str::FromStr for MockMode {
    type Err = String;

    /// Parse a mode name case-insensitively (matches the server's
    /// `MockMode.parse`). Returns an error message for blank/unknown values.
    fn from_str(value: &str) -> std::result::Result<Self, Self::Err> {
        match value.trim().to_uppercase().as_str() {
            "" => Err("mode is required (one of SIMULATE, SPY, CAPTURE)".to_string()),
            "SIMULATE" => Ok(MockMode::Simulate),
            "SPY" => Ok(MockMode::Spy),
            "CAPTURE" => Ok(MockMode::Capture),
            other => Err(format!(
                "unknown mode '{other}' (expected one of SIMULATE, SPY, CAPTURE)"
            )),
        }
    }
}

// ---------------------------------------------------------------------------
// gRPC descriptor management
// ---------------------------------------------------------------------------

/// A single gRPC method registered from an uploaded descriptor set.
///
/// Returned by [`MockServerClient::retrieve_grpc_services`] as part of a
/// [`GrpcService`]. Maps to the `methods[]` entries of the
/// `PUT /mockserver/grpc/services` wire shape.
///
/// [`MockServerClient::retrieve_grpc_services`]: crate::MockServerClient::retrieve_grpc_services
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcMethod {
    /// The simple method name (e.g. `SayHello`).
    pub name: String,

    /// Fully-qualified name of the request message type.
    pub input_type: String,

    /// Fully-qualified name of the response message type.
    pub output_type: String,

    /// Whether the method uses client-side streaming.
    pub client_streaming: bool,

    /// Whether the method uses server-side streaming.
    pub server_streaming: bool,
}

/// A gRPC service registered from an uploaded descriptor set.
///
/// Returned by [`MockServerClient::retrieve_grpc_services`]. Maps to the
/// top-level entries of the `PUT /mockserver/grpc/services` wire shape.
///
/// [`MockServerClient::retrieve_grpc_services`]: crate::MockServerClient::retrieve_grpc_services
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GrpcService {
    /// Fully-qualified service name (e.g. `helloworld.Greeter`).
    pub name: String,

    /// The methods declared by this service.
    pub methods: Vec<GrpcMethod>,
}

// ---------------------------------------------------------------------------
// SocketAddress
// ---------------------------------------------------------------------------

/// A downstream socket address (host / port / scheme) to direct a request at.
///
/// Maps to MockServer's `SocketAddress` model. Used by load-scenario steps to
/// target a specific upstream rather than relying on the `Host` header.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SocketAddress {
    /// The downstream host name or IP.
    pub host: String,

    /// The downstream port.
    pub port: u16,

    /// The scheme to connect with — `"HTTP"` or `"HTTPS"`. Defaults to `"HTTP"`
    /// on the server when omitted.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,
}

impl SocketAddress {
    /// Create a plain HTTP socket address.
    pub fn new(host: impl Into<String>, port: u16) -> Self {
        Self {
            host: host.into(),
            port,
            scheme: None,
        }
    }

    /// Set the scheme (`"HTTP"` or `"HTTPS"`).
    pub fn scheme(mut self, scheme: impl Into<String>) -> Self {
        self.scheme = Some(scheme.into());
        self
    }

    /// Convenience: an HTTPS socket address.
    pub fn https(host: impl Into<String>, port: u16) -> Self {
        Self::new(host, port).scheme("HTTPS")
    }
}

// ---------------------------------------------------------------------------
// Load scenario registry (PUT/GET/DELETE /mockserver/loadScenario[/...])
// ---------------------------------------------------------------------------

/// The interpolation curve used to ramp a value (virtual users or arrival
/// rate) from a start setpoint to an end setpoint across a ramp [`LoadStage`].
/// Maps to the `RampCurve` schema. Only meaningful for ramp stages; ignored for
/// holds and pauses.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum RampCurve {
    /// Constant slope.
    Linear,
    /// Ease-in: slow then fast.
    Quadratic,
    /// A steeper ease-in.
    Exponential,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// The kind of a [`LoadStage`].
///
/// - `Vu` — closed model: hold or ramp the number of concurrent virtual users.
/// - `Rate` — open model: hold or ramp an arrival rate in iterations/second.
/// - `Pause` — drive no load for the duration.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadStageType {
    /// Closed model — hold or ramp concurrent virtual users.
    Vu,
    /// Open model — hold or ramp an arrival rate in iterations/second.
    Rate,
    /// Drive no load for the duration.
    Pause,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// One stage of a [`LoadProfile`]: a contiguous slice of the run holding or
/// ramping a setpoint for `duration_millis`. Stages run in sequence. Maps to the
/// `LoadStage` schema.
///
/// Use the constructors [`LoadStage::vu_hold`], [`LoadStage::vu_ramp`],
/// [`LoadStage::rate_hold`], [`LoadStage::rate_ramp`] and [`LoadStage::pause`]
/// rather than building the struct directly so only the relevant fields are set
/// (and therefore serialized).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadStage {
    /// The kind of stage — `VU`, `RATE` or `PAUSE`.
    #[serde(rename = "type")]
    pub stage_type: LoadStageType,

    /// How long this stage runs in milliseconds (> 0).
    pub duration_millis: u64,

    /// Ramp shape (ramp stages only); omitted for holds and pauses.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub curve: Option<RampCurve>,

    /// VU hold: the number of virtual users to hold for the stage.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub vus: Option<u32>,

    /// VU ramp: virtual users at the start of the ramp.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_vus: Option<u32>,

    /// VU ramp: virtual users at the end of the ramp.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_vus: Option<u32>,

    /// RATE hold: arrival rate to hold, in iterations per second.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rate: Option<f64>,

    /// RATE ramp: arrival rate at the start of the ramp, in iterations/second.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_rate: Option<f64>,

    /// RATE ramp: arrival rate at the end of the ramp, in iterations/second.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_rate: Option<f64>,

    /// RATE stage only: optional cap on the auto-scaling virtual-user pool.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_vus: Option<u32>,
}

impl LoadStage {
    fn base(stage_type: LoadStageType, duration_millis: u64) -> Self {
        Self {
            stage_type,
            duration_millis,
            curve: None,
            vus: None,
            start_vus: None,
            end_vus: None,
            rate: None,
            start_rate: None,
            end_rate: None,
            max_vus: None,
        }
    }

    /// A VU stage holding `vus` virtual users for `duration_millis`.
    pub fn vu_hold(vus: u32, duration_millis: u64) -> Self {
        let mut stage = Self::base(LoadStageType::Vu, duration_millis);
        stage.vus = Some(vus);
        stage
    }

    /// A VU stage ramping from `start_vus` to `end_vus` over `duration_millis`
    /// along `curve`.
    pub fn vu_ramp(start_vus: u32, end_vus: u32, duration_millis: u64, curve: RampCurve) -> Self {
        let mut stage = Self::base(LoadStageType::Vu, duration_millis);
        stage.start_vus = Some(start_vus);
        stage.end_vus = Some(end_vus);
        stage.curve = Some(curve);
        stage
    }

    /// A RATE stage holding `rate` iterations/second for `duration_millis`.
    pub fn rate_hold(rate: f64, duration_millis: u64) -> Self {
        let mut stage = Self::base(LoadStageType::Rate, duration_millis);
        stage.rate = Some(rate);
        stage
    }

    /// A RATE stage ramping from `start_rate` to `end_rate` iterations/second
    /// over `duration_millis` along `curve`.
    pub fn rate_ramp(
        start_rate: f64,
        end_rate: f64,
        duration_millis: u64,
        curve: RampCurve,
    ) -> Self {
        let mut stage = Self::base(LoadStageType::Rate, duration_millis);
        stage.start_rate = Some(start_rate);
        stage.end_rate = Some(end_rate);
        stage.curve = Some(curve);
        stage
    }

    /// A PAUSE stage that drives no load for `duration_millis`.
    pub fn pause(duration_millis: u64) -> Self {
        Self::base(LoadStageType::Pause, duration_millis)
    }

    /// Cap the auto-scaling virtual-user pool for this RATE stage.
    pub fn max_vus(mut self, max_vus: u32) -> Self {
        self.max_vus = Some(max_vus);
        self
    }
}

/// A named load shape that expands server-side into ordinary [`LoadStage`]s.
/// Maps to the `LoadShapeType` schema.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadShapeType {
    /// Ramp up, hold the peak, ramp back down, with an optional recovery hold.
    Spike,
    /// A flight of pure-hold steps, each one "step" higher.
    Stairs,
    /// Ramp 0 to target then hold.
    RampHold,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// What a [`LoadShape`] drives. Maps to the `LoadShapeMetric` schema.
///
/// - `Vu` — concurrent virtual users (closed model).
/// - `Rate` — arrival rate in iterations/second (open model).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadShapeMetric {
    /// Concurrent virtual users (closed model).
    Vu,
    /// Arrival rate in iterations/second (open model).
    Rate,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// A declarative named load shape that expands into ordinary [`LoadStage`]s.
/// Maps to the `LoadShape` schema. Only the parameters its `type` needs are
/// read; the rest are ignored. Use a shape OR an explicit `stages` list, not
/// both.
///
/// Use the constructors [`LoadShape::spike`], [`LoadShape::stairs`] and
/// [`LoadShape::ramp_hold`] so only the relevant fields are set (and therefore
/// serialized).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadShape {
    /// The named shape — `SPIKE`, `STAIRS` or `RAMP_HOLD`.
    #[serde(rename = "type")]
    pub shape_type: LoadShapeType,

    /// What the shape drives — `VU` (default) or `RATE`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metric: Option<LoadShapeMetric>,

    /// Ramp interpolation curve used by the shape's ramps.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub curve: Option<RampCurve>,

    /// SPIKE: the level held before and after the spike.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub baseline: Option<f64>,

    /// SPIKE: the level held at the top of the spike.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peak: Option<f64>,

    /// SPIKE: duration of the baseline to peak ramp.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ramp_up_millis: Option<u64>,

    /// SPIKE: duration to hold at the peak; RAMP_HOLD: duration to hold at the
    /// target.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hold_millis: Option<u64>,

    /// SPIKE: duration of the peak to baseline ramp.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ramp_down_millis: Option<u64>,

    /// SPIKE (optional): duration to hold at baseline after the down ramp.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub recovery_hold_millis: Option<u64>,

    /// STAIRS: the level of the first step.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start: Option<f64>,

    /// STAIRS: how much each step rises above the previous one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub step: Option<f64>,

    /// STAIRS: the number of steps.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub steps: Option<u32>,

    /// STAIRS: how long each step holds at its level.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub step_duration_millis: Option<u64>,

    /// RAMP_HOLD: the level ramped up to (from 0) and then held.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target: Option<f64>,

    /// RAMP_HOLD: duration of the 0 to target ramp.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ramp_millis: Option<u64>,
}

impl LoadShape {
    fn base(shape_type: LoadShapeType) -> Self {
        Self {
            shape_type,
            metric: None,
            curve: None,
            baseline: None,
            peak: None,
            ramp_up_millis: None,
            hold_millis: None,
            ramp_down_millis: None,
            recovery_hold_millis: None,
            start: None,
            step: None,
            steps: None,
            step_duration_millis: None,
            target: None,
            ramp_millis: None,
        }
    }

    /// A SPIKE shape: ramp `baseline` to `peak` over `ramp_up_millis`, hold for
    /// `hold_millis`, then ramp back down over `ramp_down_millis`.
    pub fn spike(
        baseline: f64,
        peak: f64,
        ramp_up_millis: u64,
        hold_millis: u64,
        ramp_down_millis: u64,
    ) -> Self {
        let mut shape = Self::base(LoadShapeType::Spike);
        shape.baseline = Some(baseline);
        shape.peak = Some(peak);
        shape.ramp_up_millis = Some(ramp_up_millis);
        shape.hold_millis = Some(hold_millis);
        shape.ramp_down_millis = Some(ramp_down_millis);
        shape
    }

    /// A STAIRS shape: `steps` pure-hold steps, the first at `start` and each
    /// rising by `step`, every step holding for `step_duration_millis`.
    pub fn stairs(start: f64, step: f64, steps: u32, step_duration_millis: u64) -> Self {
        let mut shape = Self::base(LoadShapeType::Stairs);
        shape.start = Some(start);
        shape.step = Some(step);
        shape.steps = Some(steps);
        shape.step_duration_millis = Some(step_duration_millis);
        shape
    }

    /// A RAMP_HOLD shape: ramp from 0 to `target` over `ramp_millis`, then hold
    /// for `hold_millis`.
    pub fn ramp_hold(target: f64, ramp_millis: u64, hold_millis: u64) -> Self {
        let mut shape = Self::base(LoadShapeType::RampHold);
        shape.target = Some(target);
        shape.ramp_millis = Some(ramp_millis);
        shape.hold_millis = Some(hold_millis);
        shape
    }

    /// Set what the shape drives (`VU` or `RATE`).
    pub fn metric(mut self, metric: LoadShapeMetric) -> Self {
        self.metric = Some(metric);
        self
    }

    /// Set the ramp interpolation curve.
    pub fn curve(mut self, curve: RampCurve) -> Self {
        self.curve = Some(curve);
        self
    }

    /// SPIKE only: hold at baseline for `recovery_hold_millis` after the down
    /// ramp.
    pub fn recovery_hold_millis(mut self, recovery_hold_millis: u64) -> Self {
        self.recovery_hold_millis = Some(recovery_hold_millis);
        self
    }
}

/// The per-run metric a [`LoadThreshold`] evaluates. Maps to the threshold
/// `metric` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadThresholdMetric {
    /// 50th-percentile latency in milliseconds.
    LatencyP50,
    /// 95th-percentile latency in milliseconds.
    LatencyP95,
    /// 99th-percentile latency in milliseconds.
    LatencyP99,
    /// 99.9th-percentile latency in milliseconds.
    LatencyP999,
    /// Failed / requests, as a 0.0-1.0 fraction.
    ErrorRate,
    /// Throughput in requests/second over the run's elapsed time.
    ThroughputRps,
    /// Failed per-step [`LoadCheck`]s / evaluated checks, as a 0.0-1.0
    /// fraction (0 when no checks ran).
    CheckFailureRate,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// How a [`LoadThreshold`]'s observed value is compared to its threshold. Maps
/// to the threshold `comparator` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadComparator {
    /// observed < threshold.
    LessThan,
    /// observed <= threshold.
    LessThanOrEqual,
    /// observed > threshold.
    GreaterThan,
    /// observed >= threshold.
    GreaterThanOrEqual,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// An in-run pass/fail threshold for a load scenario: a per-run metric compared
/// against a value. All thresholds must hold for the run verdict to be PASS
/// (logical AND). Maps to the `LoadThreshold` schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadThreshold {
    /// The per-run metric to evaluate.
    pub metric: LoadThresholdMetric,

    /// How the observed per-run value is compared to the threshold.
    pub comparator: LoadComparator,

    /// The threshold value (milliseconds for latency metrics, a 0.0-1.0
    /// fraction for `ERROR_RATE` or `CHECK_FAILURE_RATE`, requests/second for
    /// `THROUGHPUT_RPS`).
    pub threshold: f64,
}

impl LoadThreshold {
    /// Create a threshold comparing `metric` to `threshold` using `comparator`.
    pub fn new(metric: LoadThresholdMetric, comparator: LoadComparator, threshold: f64) -> Self {
        Self {
            metric,
            comparator,
            threshold,
        }
    }
}

/// How a [`LoadPacing`] target iteration cycle is derived from its value. Maps
/// to the pacing `mode` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadPacingMode {
    /// No pacing (immediate reschedule).
    None,
    /// `value` is the target cycle in milliseconds.
    ConstantPacing,
    /// `value` is the target iterations/second per VU (cycle = 1000 / value ms).
    ConstantThroughput,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// Adaptive iteration pacing (think-time) for a load scenario: a target
/// per-virtual-user iteration cycle time. Applies only to the closed-model VU
/// loop. Maps to the `LoadPacing` schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadPacing {
    /// How the target iteration cycle is derived from `value`.
    pub mode: LoadPacingMode,

    /// For `CONSTANT_PACING` the target cycle in milliseconds; for
    /// `CONSTANT_THROUGHPUT` the target iterations/second per VU. Must be > 0
    /// when `mode` is not `NONE`.
    pub value: f64,
}

impl LoadPacing {
    /// Create a pacing rule with the given mode and value.
    pub fn new(mode: LoadPacingMode, value: f64) -> Self {
        Self { mode, value }
    }

    /// `CONSTANT_PACING`: target a per-VU iteration cycle of `cycle_millis`.
    pub fn constant_pacing(cycle_millis: f64) -> Self {
        Self::new(LoadPacingMode::ConstantPacing, cycle_millis)
    }

    /// `CONSTANT_THROUGHPUT`: target `iterations_per_second` per VU.
    pub fn constant_throughput(iterations_per_second: f64) -> Self {
        Self::new(LoadPacingMode::ConstantThroughput, iterations_per_second)
    }
}

/// The format of a [`LoadFeeder`]'s raw `data`. Maps to the feeder `format`
/// enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadFeederFormat {
    /// CSV: first line is the header row.
    Csv,
    /// JSON: an array of flat objects.
    Json,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// How a [`LoadFeeder`] selects a row each iteration. Maps to the feeder
/// `strategy` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadFeederStrategy {
    /// Cycle rows and never exhaust (default).
    Circular,
    /// Pick a uniformly random row each iteration.
    Random,
    /// Use each row once in order; COMPLETES the run when exhausted.
    Sequential,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// Parameterized test data (a data feeder) for a load scenario: an inline
/// dataset from which one row is selected per iteration and exposed to the
/// iteration's templates as `$iteration.data.<column>`. Supply EITHER `rows`
/// (the primary form) OR `data` + `format`. Maps to the `LoadFeeder` schema.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadFeeder {
    /// Inline dataset: a list of column-name to value maps, one per row.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rows: Vec<HashMap<String, String>>,

    /// Optional raw inline dataset parsed server-side into rows per `format`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<String>,

    /// The format of `data` (required when `data` is set).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub format: Option<LoadFeederFormat>,

    /// How a row is chosen each iteration.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub strategy: Option<LoadFeederStrategy>,
}

impl LoadFeeder {
    /// A feeder from an inline list of column-name to value rows.
    pub fn rows(rows: Vec<HashMap<String, String>>) -> Self {
        Self {
            rows,
            ..Self::default()
        }
    }

    /// A feeder from raw inline `data` parsed server-side as `format`.
    pub fn data(data: impl Into<String>, format: LoadFeederFormat) -> Self {
        Self {
            data: Some(data.into()),
            format: Some(format),
            ..Self::default()
        }
    }

    /// Set the row-selection strategy.
    pub fn strategy(mut self, strategy: LoadFeederStrategy) -> Self {
        self.strategy = Some(strategy);
        self
    }
}

/// Where a [`LoadCapture`] extracts its value from. Maps to the capture
/// `source` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadCaptureSource {
    /// A JSONPath over the response body.
    BodyJsonpath,
    /// A response header value.
    Header,
    /// A regex over the response body string (capture group 1).
    BodyRegex,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// A declarative cross-step capture / correlation rule: extracts a value from a
/// step's response and binds it to a variable name a later step in the same
/// iteration can reference via `$iteration.captured.<name>`. Best-effort. Maps
/// to the `LoadCapture` schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadCapture {
    /// The variable name later steps reference.
    pub name: String,

    /// Where to extract from.
    pub source: LoadCaptureSource,

    /// The JSONPath, header name, or regex driving the extraction.
    pub expression: String,

    /// Optional fallback value bound when extraction yields nothing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default_value: Option<String>,
}

impl LoadCapture {
    /// Create a capture binding `name` to the value extracted from `source` via
    /// `expression`.
    pub fn new(
        name: impl Into<String>,
        source: LoadCaptureSource,
        expression: impl Into<String>,
    ) -> Self {
        Self {
            name: name.into(),
            source,
            expression: expression.into(),
            default_value: None,
        }
    }

    /// Set the fallback value bound to the variable on no match.
    pub fn default_value(mut self, default_value: impl Into<String>) -> Self {
        self.default_value = Some(default_value.into());
        self
    }
}

/// Where a [`LoadCheck`] reads its observed value from. Maps to the check
/// `source` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadCheckSource {
    /// The response status code.
    Status,
    /// A response header (named by `header_name`).
    Header,
    /// A JSONPath over the response body (`json_path`).
    BodyJsonpath,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// How a [`LoadCheck`] compares the observed value with its expected value.
/// Maps to the check `comparator` enum. `Equals`/`NotEquals`/`Contains`/
/// `Matches` (a full-match regex) compare strings; `Gt`/`Lt`/`Gte`/`Lte` parse
/// both sides as numbers and fail the check when either is not a number.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadCheckComparator {
    Equals,
    NotEquals,
    Contains,
    Matches,
    Gt,
    Lt,
    Gte,
    Lte,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// A per-step response assertion for a load scenario: reads a value from the
/// step's response and compares it with `value`. A failing check never fails
/// the request; failures feed the `CHECK_FAILURE_RATE` threshold. Maps to the
/// `LoadCheck` schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadCheck {
    /// Where to read the observed value from.
    pub source: LoadCheckSource,

    /// The response header to read (required when `source` is `HEADER`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub header_name: Option<String>,

    /// The JSONPath over the response body (required when `source` is
    /// `BODY_JSONPATH`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub json_path: Option<String>,

    /// How the observed value is compared with `value`.
    pub comparator: LoadCheckComparator,

    /// The expected value / comparand.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,
}

impl LoadCheck {
    /// Create a check reading from `source` and comparing with `comparator`.
    pub fn new(source: LoadCheckSource, comparator: LoadCheckComparator) -> Self {
        Self {
            source,
            header_name: None,
            json_path: None,
            comparator,
            value: None,
        }
    }

    /// A check on the response status code.
    pub fn status(comparator: LoadCheckComparator, value: impl Into<String>) -> Self {
        Self::new(LoadCheckSource::Status, comparator).value(value)
    }

    /// A check on the response header `header_name`.
    pub fn header(
        header_name: impl Into<String>,
        comparator: LoadCheckComparator,
        value: impl Into<String>,
    ) -> Self {
        Self::new(LoadCheckSource::Header, comparator)
            .header_name(header_name)
            .value(value)
    }

    /// A check on the response body value at JSONPath `json_path`.
    pub fn body_json_path(
        json_path: impl Into<String>,
        comparator: LoadCheckComparator,
        value: impl Into<String>,
    ) -> Self {
        Self::new(LoadCheckSource::BodyJsonpath, comparator)
            .json_path(json_path)
            .value(value)
    }

    /// Set the response header to read.
    pub fn header_name(mut self, header_name: impl Into<String>) -> Self {
        self.header_name = Some(header_name.into());
        self
    }

    /// Set the JSONPath to evaluate over the response body.
    pub fn json_path(mut self, json_path: impl Into<String>) -> Self {
        self.json_path = Some(json_path.into());
        self
    }

    /// Set the expected value.
    pub fn value(mut self, value: impl Into<String>) -> Self {
        self.value = Some(value.into());
        self
    }
}

/// How each iteration of a load scenario selects which steps to run. Maps to
/// the `stepSelection` enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum LoadStepSelection {
    /// Run ALL steps in declared order (a multi-step user journey).
    Sequential,
    /// Run exactly ONE step per iteration chosen at random by weight.
    Weighted,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// The load profile of a load scenario: EITHER an ordered list of [`LoadStage`]s
/// run in sequence, OR a single named [`LoadShape`] that expands into stages.
/// Maps to the `LoadProfile` schema.
///
/// Use [`LoadProfile::of`] to build from a list of stages, the convenience
/// constructors [`LoadProfile::constant`] / [`LoadProfile::linear`] for a single
/// VU stage, or [`LoadProfile::shaped`] for a named shape.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadProfile {
    /// Ordered stages run one after another. Omitted (empty) when a `shape` is
    /// used.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub stages: Vec<LoadStage>,

    /// A named shape that expands server-side into stages. Use a shape OR
    /// `stages`, not both.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shape: Option<LoadShape>,
}

impl LoadProfile {
    /// A profile from an explicit list of stages.
    pub fn of(stages: Vec<LoadStage>) -> Self {
        Self {
            stages,
            shape: None,
        }
    }

    /// A profile from a single named [`LoadShape`].
    pub fn shaped(shape: LoadShape) -> Self {
        Self {
            stages: Vec::new(),
            shape: Some(shape),
        }
    }

    /// A single VU stage holding `vus` virtual users for `duration_millis`.
    pub fn constant(vus: u32, duration_millis: u64) -> Self {
        Self::of(vec![LoadStage::vu_hold(vus, duration_millis)])
    }

    /// A single linear VU ramp from `start_vus` to `end_vus` over
    /// `duration_millis`.
    pub fn linear(start_vus: u32, end_vus: u32, duration_millis: u64) -> Self {
        Self::of(vec![LoadStage::vu_ramp(
            start_vus,
            end_vus,
            duration_millis,
            RampCurve::Linear,
        )])
    }

    /// Append a stage and return the profile.
    pub fn add_stage(mut self, stage: LoadStage) -> Self {
        self.stages.push(stage);
        self
    }
}

/// A single templated request step in a load scenario. Maps to the `LoadStep`
/// schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadStep {
    /// Optional step name, used as the `step` metric label in place of the
    /// step index.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    /// Optional step-level custom labels, merged over the scenario labels
    /// (step keys win).
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub labels: HashMap<String, String>,

    /// The templated request to fire each iteration.
    pub request: HttpRequest,

    /// Optional inter-step pause (a [`Delay`]).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub think_time: Option<Delay>,

    /// Optional cross-step capture rules applied to this step's response. Each
    /// binds an extracted value to a variable name visible to SUBSEQUENT steps
    /// in the same iteration.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub captures: Vec<LoadCapture>,

    /// Optional per-step response assertions. A failing check never fails the
    /// request; failures feed the `CHECK_FAILURE_RATE` threshold.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub checks: Vec<LoadCheck>,

    /// Relative selection weight, used only when the scenario's
    /// `stepSelection` is `WEIGHTED`. Must be > 0 when `WEIGHTED`; ignored
    /// under the default `SEQUENTIAL` mode.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub weight: Option<f64>,
}

impl LoadStep {
    /// Create a step from a request matcher/template.
    pub fn new(request: HttpRequest) -> Self {
        Self {
            name: None,
            labels: HashMap::new(),
            request,
            think_time: None,
            captures: Vec::new(),
            checks: Vec::new(),
            weight: None,
        }
    }

    /// Set the step name.
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }

    /// Add a step-level custom label.
    pub fn label(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.labels.insert(key.into(), value.into());
        self
    }

    /// Set the inter-step pause.
    pub fn think_time(mut self, delay: Delay) -> Self {
        self.think_time = Some(delay);
        self
    }

    /// Append a cross-step capture rule applied to this step's response.
    pub fn capture(mut self, capture: LoadCapture) -> Self {
        self.captures.push(capture);
        self
    }

    /// Append a response check evaluated against this step's response.
    pub fn check(mut self, check: LoadCheck) -> Self {
        self.checks.push(check);
        self
    }

    /// Set the relative selection weight (used only under `WEIGHTED`
    /// `stepSelection`).
    pub fn weight(mut self, weight: f64) -> Self {
        self.weight = Some(weight);
        self
    }
}

/// An API-driven load scenario: ordered templated steps driven at a target
/// concurrency. Maps to the `LoadScenario` schema (the body of
/// `PUT /mockserver/loadScenario`, which registers the scenario in the
/// registry without running it). The unique [`name`](LoadScenario::name) is the
/// registry key used by `start`/`stop` and the per-scenario `GET`/`DELETE`
/// endpoints.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LoadScenario {
    /// Human-readable scenario name.
    pub name: String,

    /// Optional scenario-level custom labels, exported on every load
    /// measurement (as Prometheus labels only for allow-listed keys).
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub labels: HashMap<String, String>,

    /// Template engine for per-iteration rendering — `"VELOCITY"` (default) or
    /// `"MUSTACHE"`. (JavaScript is rejected for load steps.)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub template_type: Option<String>,

    /// Optional hard cap on the total number of requests dispatched.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_requests: Option<u64>,

    /// Optional delay (milliseconds) applied between a `start` request being
    /// accepted and the scenario actually beginning to drive load. Honoured by
    /// `PUT /mockserver/loadScenario/start`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_delay_millis: Option<u64>,

    /// Optional in-run pass/fail thresholds; the run carries a PASS verdict iff
    /// all hold, FAIL otherwise. Empty/omitted means no verdict is computed.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub thresholds: Vec<LoadThreshold>,

    /// When true, a FAIL verdict aborts the run early. Default false (omitted).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub abort_on_fail: bool,

    /// Suppress `abort_on_fail` for the first N milliseconds of the run so noisy
    /// startup samples cannot trigger a premature abort.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub abort_grace_millis: Option<u64>,

    /// Optional adaptive iteration pacing (closed-model VU loop only).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pacing: Option<LoadPacing>,

    /// Optional parameterized test data (a data feeder).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub feeder: Option<LoadFeeder>,

    /// How each iteration selects which steps to run — `SEQUENTIAL` (default,
    /// omitted) or `WEIGHTED`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub step_selection: Option<LoadStepSelection>,

    /// The ramp profile.
    pub profile: LoadProfile,

    /// Ordered list of request steps fired in sequence each iteration (max 50).
    pub steps: Vec<LoadStep>,
}

impl LoadScenario {
    /// Create a scenario with the given name, profile and steps.
    pub fn new(name: impl Into<String>, profile: LoadProfile, steps: Vec<LoadStep>) -> Self {
        Self {
            name: name.into(),
            labels: HashMap::new(),
            template_type: None,
            max_requests: None,
            start_delay_millis: None,
            thresholds: Vec::new(),
            abort_on_fail: false,
            abort_grace_millis: None,
            pacing: None,
            feeder: None,
            step_selection: None,
            profile,
            steps,
        }
    }

    /// Add a scenario-level custom label.
    pub fn label(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.labels.insert(key.into(), value.into());
        self
    }

    /// Add an in-run pass/fail threshold.
    pub fn threshold(mut self, threshold: LoadThreshold) -> Self {
        self.thresholds.push(threshold);
        self
    }

    /// Set whether a FAIL verdict aborts the run early.
    pub fn abort_on_fail(mut self, abort_on_fail: bool) -> Self {
        self.abort_on_fail = abort_on_fail;
        self
    }

    /// Set the abort grace window (milliseconds) for `abort_on_fail`.
    pub fn abort_grace_millis(mut self, abort_grace_millis: u64) -> Self {
        self.abort_grace_millis = Some(abort_grace_millis);
        self
    }

    /// Set the adaptive iteration pacing.
    pub fn pacing(mut self, pacing: LoadPacing) -> Self {
        self.pacing = Some(pacing);
        self
    }

    /// Set the parameterized test data feeder.
    pub fn feeder(mut self, feeder: LoadFeeder) -> Self {
        self.feeder = Some(feeder);
        self
    }

    /// Set how each iteration selects which steps to run.
    pub fn step_selection(mut self, step_selection: LoadStepSelection) -> Self {
        self.step_selection = Some(step_selection);
        self
    }

    /// Set the template engine (`"VELOCITY"` or `"MUSTACHE"`).
    pub fn template_type(mut self, template_type: impl Into<String>) -> Self {
        self.template_type = Some(template_type.into());
        self
    }

    /// Set the hard cap on total requests dispatched.
    pub fn max_requests(mut self, max_requests: u64) -> Self {
        self.max_requests = Some(max_requests);
        self
    }

    /// Set the delay (milliseconds) before the scenario begins driving load
    /// once started.
    pub fn start_delay_millis(mut self, start_delay_millis: u64) -> Self {
        self.start_delay_millis = Some(start_delay_millis);
        self
    }
}

// ---------------------------------------------------------------------------
// SLO verdicts (PUT /mockserver/verifySLO)
// ---------------------------------------------------------------------------

/// A single service-level objective over the recorded SLI samples. Maps to the
/// `SloObjective` schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SloObjective {
    /// The indicator to evaluate — one of `LATENCY_P50`, `LATENCY_P95`,
    /// `LATENCY_P99`, `ERROR_RATE`.
    pub sli: String,

    /// How the observed value is compared to the threshold — one of
    /// `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`,
    /// `GREATER_THAN_OR_EQUAL`.
    pub comparator: String,

    /// The objective threshold (milliseconds for latency SLIs, a 0.0–1.0
    /// fraction for `ERROR_RATE`).
    pub threshold: f64,

    /// Which recorded traffic to evaluate — `"FORWARD"` (default) or
    /// `"INBOUND"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scope: Option<String>,
}

impl SloObjective {
    /// Create an objective.
    pub fn new(sli: impl Into<String>, comparator: impl Into<String>, threshold: f64) -> Self {
        Self {
            sli: sli.into(),
            comparator: comparator.into(),
            threshold,
            scope: None,
        }
    }

    /// Set the evaluation scope (`"FORWARD"` or `"INBOUND"`).
    pub fn scope(mut self, scope: impl Into<String>) -> Self {
        self.scope = Some(scope.into());
        self
    }
}

/// The time window of an SLO evaluation. Maps to the `SloCriteria.window`
/// object.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SloWindow {
    /// `"LOOKBACK"` (default) or `"EXPLICIT"`.
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub window_type: Option<String>,

    /// LOOKBACK: window length ending now.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lookback_millis: Option<u64>,

    /// EXPLICIT: window start in epoch milliseconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub from_epoch_millis: Option<u64>,

    /// EXPLICIT: window end in epoch milliseconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub to_epoch_millis: Option<u64>,
}

impl SloWindow {
    /// A LOOKBACK window of `millis` ending now.
    pub fn lookback(millis: u64) -> Self {
        Self {
            window_type: Some("LOOKBACK".to_string()),
            lookback_millis: Some(millis),
            ..Default::default()
        }
    }

    /// An EXPLICIT window between two epoch-millisecond bounds.
    pub fn explicit(from_epoch_millis: u64, to_epoch_millis: u64) -> Self {
        Self {
            window_type: Some("EXPLICIT".to_string()),
            from_epoch_millis: Some(from_epoch_millis),
            to_epoch_millis: Some(to_epoch_millis),
            ..Default::default()
        }
    }
}

/// A named set of service-level objectives over a time window. Maps to the
/// `SloCriteria` schema (the body of `PUT /mockserver/verifySLO`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SloCriteria {
    /// Human-readable criteria name, echoed back in the verdict.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    /// The time window to evaluate over.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub window: Option<SloWindow>,

    /// Minimum samples required in the window; below this the verdict is
    /// INCONCLUSIVE.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub minimum_sample_count: Option<u64>,

    /// Optional list of upstream hosts to restrict the evaluation to.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub upstream_hosts: Option<Vec<String>>,

    /// The objectives (the verdict is the logical AND of all of them).
    pub objectives: Vec<SloObjective>,
}

impl SloCriteria {
    /// Create criteria from a set of objectives.
    pub fn new(objectives: Vec<SloObjective>) -> Self {
        Self {
            name: None,
            window: None,
            minimum_sample_count: None,
            upstream_hosts: None,
            objectives,
        }
    }

    /// Set the criteria name.
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }

    /// Set the evaluation window.
    pub fn window(mut self, window: SloWindow) -> Self {
        self.window = Some(window);
        self
    }

    /// Set the minimum sample count.
    pub fn minimum_sample_count(mut self, count: u64) -> Self {
        self.minimum_sample_count = Some(count);
        self
    }

    /// Restrict the evaluation to the given upstream hosts.
    pub fn upstream_hosts(mut self, hosts: Vec<String>) -> Self {
        self.upstream_hosts = Some(hosts);
        self
    }
}

/// The evaluated result of a single objective. Maps to the `SloObjectiveResult`
/// schema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SloObjectiveResult {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sli: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comparator: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub threshold: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub observed_value: Option<f64>,
    /// `PASS`, `FAIL` or `INCONCLUSIVE`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

/// The overall verdict of an SLO evaluation. Maps to the `SloVerdict` schema —
/// the response of `PUT /mockserver/verifySLO`.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SloVerdict {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// `PASS`, `FAIL` or `INCONCLUSIVE`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub window_from_epoch_millis: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub window_to_epoch_millis: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sample_count: Option<u64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub objective_results: Vec<SloObjectiveResult>,
}

impl SloVerdict {
    /// Whether the overall verdict is `PASS`.
    pub fn is_pass(&self) -> bool {
        self.result.as_deref() == Some("PASS")
    }

    /// Whether the overall verdict is `FAIL`.
    pub fn is_fail(&self) -> bool {
        self.result.as_deref() == Some("FAIL")
    }

    /// Whether the overall verdict is `INCONCLUSIVE`.
    pub fn is_inconclusive(&self) -> bool {
        self.result.as_deref() == Some("INCONCLUSIVE")
    }
}

// ---------------------------------------------------------------------------
// Preemption (PUT/GET/DELETE /mockserver/preemption)
// ---------------------------------------------------------------------------

/// Preemption simulation parameters (all fields optional). Maps to the
/// `PreemptionRequest` schema (the body of `PUT /mockserver/preemption`).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PreemptionRequest {
    /// How draining is signalled — `"reject503"`, `"goaway"` or `"both"`
    /// (default).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,

    /// How long in-flight requests are allowed to drain (clamped server-side).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drain_millis: Option<u64>,

    /// Auto-uncordon after this many milliseconds (dead-man's switch); `0`
    /// (default) means no auto-uncordon.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl_millis: Option<u64>,

    /// HTTP/2 GOAWAY `last_stream_id` to advertise; `-1` (default) lets the
    /// server choose.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_stream_id: Option<i64>,
}

impl PreemptionRequest {
    /// An empty request (server defaults: mode "both", default drain, no TTL).
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the signalling mode (`"reject503"`, `"goaway"` or `"both"`).
    pub fn mode(mut self, mode: impl Into<String>) -> Self {
        self.mode = Some(mode.into());
        self
    }

    /// Set the drain window in milliseconds.
    pub fn drain_millis(mut self, millis: u64) -> Self {
        self.drain_millis = Some(millis);
        self
    }

    /// Set the auto-uncordon TTL in milliseconds.
    pub fn ttl_millis(mut self, millis: u64) -> Self {
        self.ttl_millis = Some(millis);
        self
    }

    /// Set the HTTP/2 GOAWAY `last_stream_id` to advertise.
    pub fn last_stream_id(mut self, id: i64) -> Self {
        self.last_stream_id = Some(id);
        self
    }
}

/// The current cordon/drain status of the server. Maps to the
/// `PreemptionStatus` schema — the response of `PUT`/`GET /mockserver/preemption`.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PreemptionStatus {
    /// `"inactive"`, `"draining"` or `"drained"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub state: Option<String>,

    /// Number of requests currently in flight.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub in_flight: Option<u64>,

    /// Milliseconds left in the drain window.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drain_remaining_millis: Option<u64>,

    /// Active signalling mode (omitted when inactive).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
}

// ---------------------------------------------------------------------------
// Service chaos (PUT /mockserver/serviceChaos)
// ---------------------------------------------------------------------------

/// An HTTP chaos / fault-injection profile for a host or expectation. Maps to
/// the `HttpChaosProfile` schema. Captures the commonly-used fields; the model
/// carries an `extra` map for any additional server-supported keys.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpChaosProfile {
    /// HTTP error status code to return instead of the real response.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_status: Option<u16>,

    /// Probability (0.0–1.0) that a request triggers the error.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_probability: Option<f64>,

    /// Injected latency (a [`Delay`]).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub latency: Option<Delay>,

    /// When true, drops the TCP connection without responding.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub connection_drop: Option<bool>,

    /// Fixed seed for deterministic probabilistic outcomes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seed: Option<i64>,

    /// Literal `Retry-After` header value returned with an injected error.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after: Option<String>,

    /// Probability (0.0–1.0) of dropping the TCP connection without responding.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drop_connection_probability: Option<f64>,

    /// Let the first N requests succeed before chaos becomes active.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub succeed_first: Option<i64>,

    /// Number of requests to fail once chaos is active.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fail_request_count: Option<i64>,

    /// Time-based outage: chaos activates this many ms after first match.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub outage_after_millis: Option<i64>,

    /// Time-based outage: chaos stays active this many ms then self-heals.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub outage_duration_millis: Option<i64>,

    /// Keep only this leading fraction (0.0–1.0) of the response body.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub truncate_body_at_fraction: Option<f64>,

    /// Corrupt the response body so it fails to parse.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub malformed_body: Option<bool>,

    /// Dribble the response body in chunks of this many bytes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub slow_response_chunk_size: Option<i64>,

    /// Delay between slow-response chunks.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub slow_response_chunk_delay: Option<Delay>,

    /// Shared quota counter key.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quota_name: Option<String>,

    /// Max requests allowed per quota window.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quota_limit: Option<i64>,

    /// Quota fixed-window length in milliseconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quota_window_millis: Option<i64>,

    /// Status returned when the quota is exceeded (default 429).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quota_error_status: Option<u16>,

    /// Ramp error/drop probabilities linearly over this many ms from first match.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub degradation_ramp_millis: Option<i64>,

    /// Rewrite the response body as a GraphQL error envelope.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub graphql_errors: Option<bool>,

    /// Message in `errors[0].message` of the GraphQL error envelope.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub graphql_error_message: Option<String>,

    /// Value for `errors[0].extensions.code`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub graphql_error_code: Option<String>,

    /// Whether `data` is null (default true) in the GraphQL error envelope.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub graphql_nullify_data: Option<bool>,

    /// Any additional fields the server supports that are not modelled above.
    #[serde(flatten)]
    pub extra: HashMap<String, serde_json::Value>,
}

impl HttpChaosProfile {
    /// Create an empty chaos profile.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the error status code returned on fault.
    pub fn error_status(mut self, status: u16) -> Self {
        self.error_status = Some(status);
        self
    }

    /// Set the probability (0.0–1.0) of triggering the error.
    pub fn error_probability(mut self, probability: f64) -> Self {
        self.error_probability = Some(probability);
        self
    }

    /// Set the injected latency.
    pub fn latency(mut self, latency: Delay) -> Self {
        self.latency = Some(latency);
        self
    }

    /// Drop the TCP connection without responding.
    pub fn connection_drop(mut self, drop: bool) -> Self {
        self.connection_drop = Some(drop);
        self
    }

    /// Set the deterministic seed.
    pub fn seed(mut self, seed: i64) -> Self {
        self.seed = Some(seed);
        self
    }
}

// ---------------------------------------------------------------------------
// Chaos experiment (PUT /mockserver/chaosExperiment)
// ---------------------------------------------------------------------------

/// A single stage of a chaos experiment. Maps to a `ChaosExperiment.stages[]`
/// entry.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ChaosStage {
    /// How long this stage runs before advancing (max 86_400_000 = 24h).
    pub duration_millis: u64,

    /// Map of host -> chaos profile to apply during this stage.
    pub profiles: HashMap<String, HttpChaosProfile>,
}

impl ChaosStage {
    /// Create a stage running for `duration_millis`.
    pub fn new(duration_millis: u64) -> Self {
        Self {
            duration_millis,
            profiles: HashMap::new(),
        }
    }

    /// Add a host -> chaos profile to apply during the stage.
    pub fn profile(mut self, host: impl Into<String>, profile: HttpChaosProfile) -> Self {
        self.profiles.insert(host.into(), profile);
        self
    }
}

/// A scheduled multi-stage chaos experiment definition. Maps to the
/// `ChaosExperiment` schema (the body of `PUT /mockserver/chaosExperiment`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ChaosExperiment {
    /// Human-readable experiment name.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    /// Whether to loop back to stage 0 after the last stage completes (default
    /// false). Serialized as `loop` on the wire.
    #[serde(rename = "loop", skip_serializing_if = "Option::is_none")]
    pub loop_back: Option<bool>,

    /// The ordered sequence of stages.
    pub stages: Vec<ChaosStage>,
}

impl ChaosExperiment {
    /// Create an experiment from an ordered list of stages.
    pub fn new(stages: Vec<ChaosStage>) -> Self {
        Self {
            name: None,
            loop_back: None,
            stages,
        }
    }

    /// Set the experiment name.
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }

    /// Set whether the experiment loops back to the first stage.
    pub fn loop_back(mut self, loop_back: bool) -> Self {
        self.loop_back = Some(loop_back);
        self
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_grpc_services_deserialize_from_server_wire_shape() {
        // Mirrors the JSON array produced by `PUT /mockserver/grpc/services`
        // in mockserver-core HttpState.java (camelCase keys, full type names).
        let wire = r#"[
            {
                "name": "helloworld.Greeter",
                "methods": [
                    {
                        "name": "SayHello",
                        "inputType": "helloworld.HelloRequest",
                        "outputType": "helloworld.HelloReply",
                        "clientStreaming": false,
                        "serverStreaming": false
                    },
                    {
                        "name": "LotsOfReplies",
                        "inputType": "helloworld.HelloRequest",
                        "outputType": "helloworld.HelloReply",
                        "clientStreaming": false,
                        "serverStreaming": true
                    }
                ]
            }
        ]"#;

        let services: Vec<GrpcService> = serde_json::from_str(wire).unwrap();
        assert_eq!(services.len(), 1);
        let svc = &services[0];
        assert_eq!(svc.name, "helloworld.Greeter");
        assert_eq!(svc.methods.len(), 2);

        let unary = &svc.methods[0];
        assert_eq!(unary.name, "SayHello");
        assert_eq!(unary.input_type, "helloworld.HelloRequest");
        assert_eq!(unary.output_type, "helloworld.HelloReply");
        assert!(!unary.client_streaming);
        assert!(!unary.server_streaming);

        let server_stream = &svc.methods[1];
        assert_eq!(server_stream.name, "LotsOfReplies");
        assert!(!server_stream.client_streaming);
        assert!(server_stream.server_streaming);
    }

    #[test]
    fn test_grpc_method_serializes_with_camel_case_keys() {
        let method = GrpcMethod {
            name: "BidiChat".into(),
            input_type: "chat.Message".into(),
            output_type: "chat.Message".into(),
            client_streaming: true,
            server_streaming: true,
        };
        let value = serde_json::to_value(&method).unwrap();
        assert_eq!(value["name"], "BidiChat");
        assert_eq!(value["inputType"], "chat.Message");
        assert_eq!(value["outputType"], "chat.Message");
        assert_eq!(value["clientStreaming"], true);
        assert_eq!(value["serverStreaming"], true);
    }

    #[test]
    fn test_grpc_services_empty_array() {
        let services: Vec<GrpcService> = serde_json::from_str("[]").unwrap();
        assert!(services.is_empty());
    }

    #[test]
    fn test_grpc_service_round_trips() {
        let original = GrpcService {
            name: "helloworld.Greeter".into(),
            methods: vec![GrpcMethod {
                name: "SayHello".into(),
                input_type: "helloworld.HelloRequest".into(),
                output_type: "helloworld.HelloReply".into(),
                client_streaming: false,
                server_streaming: false,
            }],
        };
        let json = serde_json::to_string(&original).unwrap();
        let parsed: GrpcService = serde_json::from_str(&json).unwrap();
        assert_eq!(original, parsed);
    }
}
