package org.mockserver.model;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;

public class MediaTypeTest {

    @Test
    public void shouldParseRFC7231ContentTypeExamples() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "text/html;charset=utf-8",
            "text/html;charset=UTF-8",
            "Text/HTML;Charset=\"utf-8\"",
            "text/html; charset=\"utf-8\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample), is(new MediaType("text", "html").withCharset("utf-8")));
        }
    }

    @Test
    public void shouldParseContentTypeWithSpecialCharacters() {
        assertThat(MediaType.parse("application/my.type+xml"), is(new MediaType("application", "my.type+xml")));
        assertThat(MediaType.parse("application/my.type+xml;charset=utf-8"), is(new MediaType("application", "my.type+xml").withCharset("utf-8")));
        assertThat(MediaType.parse("application/my.type+xml; Charset=\"utf-8\""), is(new MediaType("application", "my.type+xml").withCharset("utf-8")));
    }

    @Test
    public void shouldParseWithoutCharset() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "text/html",
            "text/html;",
            "Text/HTML",
            "Text/HTML;",
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample), is(new MediaType("text", "html")));
        }
    }

    @Test
    public void shouldParseWithoutType() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "charset=utf-8",
            "charset=UTF-8",
            "Charset=\"utf-8\"",
            "charset=\"utf-8\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample), is(new MediaType(null, null).withCharset("utf-8")));
        }
    }

    @Test
    public void shouldSerialiseToStringRFC7231ContentTypeExamples() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "text/html;charset=utf-8",
            "text/html;charset=UTF-8",
            "Text/HTML;Charset=\"utf-8\"",
            "text/html; charset=\"utf-8\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample).toString(), is("text/html; charset=utf-8"));
        }
    }

    @Test
    public void shouldSerialiseToStringWithoutCharset() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "text/html",
            "text/html;",
            "Text/HTML",
            "Text/HTML;",
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample).toString(), is("text/html"));
        }
    }

    @Test
    public void shouldSerialiseToStringWithoutType() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "charset=utf-8",
            "charset=UTF-8",
            "Charset=\"utf-8\"",
            "charset=\"utf-8\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample).toString(), is("charset=utf-8"));
        }
    }

    @Test
    public void shouldSerialiseToStringFromConstructor() {
        assertThat(MediaType.create("text", "html").withCharset(StandardCharsets.UTF_8).toString(), is("text/html; charset=utf-8"));
        assertThat(MediaType.create("text", "html").withCharset("utf-8").toString(), is("text/html; charset=utf-8"));
        assertThat(MediaType.create("text", "html").withCharset("UTF-8").toString(), is("text/html; charset=utf-8"));
        assertThat(MediaType.create(null, null).withCharset(StandardCharsets.UTF_8).toString(), is("charset=utf-8"));
    }

    @Test
    public void shouldSupportSettingCharsetObject() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "text/html;charset=utf-8",
            "text/html;charset=UTF-8",
            "Text/HTML;Charset=\"utf-8\"",
            "text/html; charset=\"utf-8\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample), is(MediaType.create("text", "html").withCharset(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void shouldSupportSettingCharsetObjectWithoutType() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "charset=utf-8",
            "charset=UTF-8",
            "Charset=\"utf-8\"",
            "charset=\"utf-8\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample), is(MediaType.create(null, null).withCharset(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void shouldSupportAdditionParameters() {
        assertThat(MediaType.parse("application/soap+xml;charset=UTF-8;action=\"somerandomstuff\""), is(new MediaType("application", "soap+xml", ImmutableMap.of(
            "action", "somerandomstuff"
        )).withCharset(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldSupportAdditionParametersWithEqualsInTheValue() {
        MediaType parse = MediaType.parse("multipart/form-data; boundary=\"===1597684222645===\"; charset=utf-8");
        assertThat(parse.toString(), is("multipart/form-data; boundary====1597684222645===; charset=utf-8"));
        Map<String, String> parameters = parse.getParameters();
        assertThat(parameters, allOf(
            hasEntry("boundary", "===1597684222645==="),
            hasEntry("charset", "utf-8")
        ));
    }

    @Test
    public void shouldSerialiseToStringWithAdditionParameters() {
        assertThat(new MediaType("application", "soap+xml", ImmutableMap.of(
            "action", "somerandomstuff"
        )).withCharset(StandardCharsets.UTF_8).toString(), is("application/soap+xml; action=somerandomstuff; charset=utf-8"));
    }

    @Test
    public void shouldSerialiseToStringWithAdditionParametersAndNoCharset() {
        assertThat(new MediaType("application", "soap+xml", ImmutableMap.of(
            "action", "somerandomstuff"
        )).toString(), is("application/soap+xml; action=somerandomstuff"));
    }

    @Test
    public void shouldParseContentTypeWithAdditionalParameters() {
        // when
        String[] rfcContentTypeExamples = new String[]{
            "application/soap+xml;charset=UTF-8;action=\"somerandomstuff\"",
            "application/soap+xml;charset=UTF-8;action=somerandomstuff",
            "application/soap+xml; charset=UTF-8; action=\"somerandomstuff\"",
            "charset=UTF-8;action=\"somerandomstuff\""
        };

        for (String rfcContentTypeExample : rfcContentTypeExamples) {
            // then
            assertThat(rfcContentTypeExample, MediaType.parse(rfcContentTypeExample).getCharset(), is(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void shouldDefaultServerSentEventsAndNdjsonToUtf8() {
        assertThat(MediaType.parse("text/event-stream").getCharsetOrDefault(), is(StandardCharsets.UTF_8));
        assertThat(MediaType.parse("TEXT/EVENT-STREAM").getCharsetOrDefault(), is(StandardCharsets.UTF_8));
        assertThat(MediaType.parse("application/x-ndjson").getCharsetOrDefault(), is(StandardCharsets.UTF_8));
        assertThat("an explicit charset still wins", MediaType.parse("text/event-stream; charset=iso-8859-1").getCharsetOrDefault(), is(StandardCharsets.ISO_8859_1));
        assertThat("other text keeps its default", MediaType.parse("text/plain").getCharsetOrDefault(), is(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void shouldReturnDefaultCharset() {
        List<String> textContentTypes = Arrays.asList(
            "text/plain",
            "text/html"
        );
        for (String contentType : textContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat("\"" + contentType + "\" should be default charset CharsetUtil.ISO_8859_1", parse.getCharsetOrDefault(), is(StandardCharsets.ISO_8859_1));
        }
        List<String> jsonAndXmlContentTypes = Arrays.asList(
            "",
            "application/json",
            "application/xml",
            "text/xml",
            "text/json",
            "application/atom+xml",
            "application/atom+json"
        );
        for (String contentType : jsonAndXmlContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat("\"" + contentType + "\" should be default charset CharsetUtil.ISO_8859_1", parse.getCharsetOrDefault(), is(StandardCharsets.UTF_8));
        }
        List<String> binaryContentTypes = Arrays.asList(
            "application/applixware",
            "application/font-tdpfr",
            "application/java-archive",
            "application/java-serialized-object",
            "application/java-vm",
            "application/mp4",
            "application/octet-stream",
            "application/pdf",
            "application/pkcs10",
            "application/pkix-cert",
            "application/x-font-bdf",
            "application/x-font-ghostscript",
            "application/x-font-linux-psf",
            "application/x-font-otf",
            "application/x-font-pcf",
            "application/x-font-snf",
            "application/x-font-ttf",
            "application/x-font-type1",
            "application/font-woff",
            "application/x-java-jnlp-file",
            "application/x-latex",
            "application/x-shockwave-flash",
            "application/x-silverlight-app",
            "application/x-stuffit",
            "application/x-tar",
            "application/x-tex",
            "application/x-tex-tfm",
            "application/x-x509-ca-cert",
            "application/zip",
            "audio/midi",
            "audio/mp4",
            "audio/mpeg",
            "audio/ogg",
            "audio/x-aiff",
            "audio/x-wav",
            "audio/xm",
            "image/bmp",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/sgi",
            "image/tiff",
            "image/x-xbitmap",
            "video/jpeg",
            "video/mp4",
            "video/mpeg",
            "video/ogg",
            "video/quicktime",
            "video/x-msvideo",
            "video/x-sgi-movie"
        );
        for (String contentType : binaryContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat("\"" + contentType + "\" should be no default charset", parse.getCharsetOrDefault(), nullValue());
        }
    }

    @Test
    public void shouldNotDetectAsBinaryContentTypeAsJsonOrStringOrXml() {
        List<String> binaryContentTypes = Arrays.asList(
            "application/applixware",
            "application/font-tdpfr",
            "application/java-archive",
            "application/java-serialized-object",
            "application/java-vm",
            "application/mp4",
            "application/octet-stream",
            "application/pdf",
            "application/pkcs10",
            "application/pkix-cert",
            "application/x-font-bdf",
            "application/x-font-ghostscript",
            "application/x-font-linux-psf",
            "application/x-font-otf",
            "application/x-font-pcf",
            "application/x-font-snf",
            "application/x-font-ttf",
            "application/x-font-type1",
            "application/font-woff",
            "application/x-java-jnlp-file",
            "application/x-latex",
            "application/x-shockwave-flash",
            "application/x-silverlight-app",
            "application/x-stuffit",
            "application/x-tar",
            "application/x-tex",
            "application/x-tex-tfm",
            "application/x-x509-ca-cert",
            "application/zip",
            "audio/midi",
            "audio/mp4",
            "audio/mpeg",
            "audio/ogg",
            "audio/x-aiff",
            "audio/x-wav",
            "audio/xm",
            "image/bmp",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/sgi",
            "image/tiff",
            "image/x-xbitmap",
            "video/jpeg",
            "video/mp4",
            "video/mpeg",
            "video/ogg",
            "video/quicktime",
            "video/x-msvideo",
            "video/x-sgi-movie"
        );
        for (String contentType : binaryContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should not be json", parse.isJson(), is(false));
            assertThat(contentType + " should not be xml", parse.isXml(), is(false));
            assertThat(contentType + " should not be string", parse.isString(), is(false));
        }
    }

    @Test
    public void shouldNotDetectBinaryContentTypeWithCharsetAsString() {
        List<String> binaryWithCharset = Arrays.asList(
            "application/octet-stream; charset=UTF-8",
            "application/pdf; charset=utf-8",
            "application/zip; charset=utf-8",
            "image/png; charset=utf-8",
            "video/mp4; charset=utf-8",
            "audio/mpeg; charset=utf-8",
            "application/binary; charset=utf-8",
            "application/gzip; charset=utf-8",
            "application/x-protobuf; charset=utf-8"
        );
        for (String contentType : binaryWithCharset) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should not be string", parse.isString(), is(false));
        }
    }

    @Test
    public void shouldDetectUnknownContentTypeWithCharsetAsString() {
        List<String> unknownWithCharset = Arrays.asList(
            "application/java; charset=utf-8",
            "application/encrypted; charset=utf-8",
            "application/x-custom; charset=utf-8",
            "application/vnd.example.custom; charset=utf-8"
        );
        for (String contentType : unknownWithCharset) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should be string", parse.isString(), is(true));
        }
    }

    @Test
    public void shouldDetectTextFormatsWithoutATextTypeAsString() {
        // application/yaml (RFC 9512), application/x-yaml and application/graphql are UTF-8 text but
        // have neither a "text" type nor a "+json"/"+xml" suffix. Treating them as binary made
        // getBodyAsString() return base64, corrupting YAML specs and GraphQL SDL documents sent with
        // their natural content type.
        List<String> textContentTypes = Arrays.asList(
            "application/yaml",
            "application/x-yaml",
            "text/yaml",
            "application/yaml; charset=utf-8",
            "application/graphql",
            "application/graphql; charset=utf-8"
        );
        for (String contentType : textContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should be string", parse.isString(), is(true));
        }
    }

    @Test
    public void shouldDetectAsJson() {
        List<String> jsonContentTypes = Arrays.asList(
            "application/json",
            "text/json"
        );
        for (String contentType : jsonContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should be json", parse.isJson(), is(true));
        }
    }

    @Test
    public void shouldNotDetectAsJson() {
        List<String> jsonContentTypes = Arrays.asList(
            "application/x-ndjson"
        );
        for (String contentType : jsonContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should not be json", parse.isJson(), is(false));
        }
    }

    @Test
    public void shouldDetectAsXml() {
        List<String> xmlContentTypes = Arrays.asList(
            "application/xml",
            "text/xml",
            "application/xhtml+xml",
            "application/vnd.mozilla.xul+xml",
            "application/wspolicy+xml"
        );
        for (String contentType : xmlContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should be xml", parse.isXml(), is(true));
        }
    }

    @Test
    public void shouldDetectAsString() {
        List<String> xmlContentTypes = Arrays.asList(
            "application/json",
            "application/text",
            "application/html",
            "text/json",
            "text/plain",
            "application/xml",
            "text/xml",
            "application/xhtml+xml",
            "application/vnd.mozilla.xul+xml",
            "application/wspolicy+xml",
            "charset=utf-8",
            "charset=UTF-8",
            "Charset=\"utf-8\"",
            "charset=\"utf-8\"",
            "text/html;charset=utf-8",
            "text/html;charset=UTF-8",
            "Text/HTML;Charset=\"utf-8\"",
            "text/html; charset=\"utf-8\""
        );
        for (String contentType : xmlContentTypes) {
            MediaType parse = MediaType.parse(contentType);
            assertThat(contentType + " should be xml", parse.isString(), is(true));
        }
    }

    @Test
    public void shouldEqual() {
        String[] contentTypeExamples = new String[]{
            "application/soap+xml;charset=UTF-8;action=\"somerandomstuff\"",
            "application/soap+xml;charset=UTF-8;action=somerandomstuff",
            "application/soap+xml; charset=UTF-8; action=\"somerandomstuff\"",
            "charset=UTF-8;action=\"somerandomstuff\"",
            "application/json",
            "application/text",
            "application/html",
            "text/json",
            "text/plain",
            "application/xml",
            "text/xml",
            "application/xhtml+xml",
            "application/vnd.mozilla.xul+xml",
            "application/wspolicy+xml",
            "charset=utf-8",
            "charset=UTF-8",
            "Charset=\"utf-8\"",
            "charset=\"utf-8\"",
            "text/html;charset=utf-8",
            "text/html;charset=UTF-8",
            "Text/HTML;Charset=\"utf-8\"",
            "text/html; charset=\"utf-8\"",
            "application/applixware",
            "application/font-tdpfr",
            "application/java-archive",
            "application/java-serialized-object",
            "application/java-vm",
            "application/mp4",
            "application/octet-stream",
            "application/pdf",
            "application/pkcs10",
            "application/pkix-cert",
            "application/x-font-bdf",
            "application/x-font-ghostscript",
            "application/x-font-linux-psf",
            "application/x-font-otf",
            "application/x-font-pcf",
            "application/x-font-snf",
            "application/x-font-ttf",
            "application/x-font-type1",
            "application/font-woff",
            "application/x-java-jnlp-file",
            "application/x-latex",
            "application/x-shockwave-flash",
            "application/x-silverlight-app",
            "application/x-stuffit",
            "application/x-tar",
            "application/x-tex",
            "application/x-tex-tfm",
            "application/x-x509-ca-cert",
            "application/zip",
            "audio/midi",
            "audio/mp4",
            "audio/mpeg",
            "audio/ogg",
            "audio/x-aiff",
            "audio/x-wav",
            "audio/xm",
            "image/bmp",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/sgi",
            "image/tiff",
            "image/x-xbitmap",
            "video/jpeg",
            "video/mp4",
            "video/mpeg",
            "video/ogg",
            "video/quicktime",
            "video/x-msvideo",
            "video/x-sgi-movie"
        };
        for (String contentTypeExample : contentTypeExamples) {
            assertThat(contentTypeExample, MediaType.parse(contentTypeExample), is(MediaType.parse(MediaType.parse(contentTypeExample).toString())));
        }
        assertThat(MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16), is(MediaType.parse(MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16).toString())));
    }

    // ---------------------------------------------------------------------------------------------
    // Parse cache (perf: MediaType.parse is on every request-with-body path). The cache must never
    // change parse RESULTS, must never conflate charset-case variants, must return an instance that
    // cannot be mutated through getParameters() (a shared cached instance would otherwise be a
    // cross-request data leak), and must stay bounded because the Content-Type header is
    // attacker-controlled off the wire.
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, MediaType> parseCache() throws Exception {
        Field field = MediaType.class.getDeclaredField("PARSE_CACHE");
        field.setAccessible(true);
        return (Map<String, MediaType>) field.get(null);
    }

    private static int maxCacheEntries() throws Exception {
        Field field = MediaType.class.getDeclaredField("MAX_CACHE_ENTRIES");
        field.setAccessible(true);
        return (int) field.get(null);
    }

    @Test
    public void shouldCacheParsedContentTypeAndReturnSharedInstanceOnHit() throws Exception {
        // given - a header unique to this run so the assertion is independent of other tests' cache use
        String subtype = "x-cache-probe-" + UUID.randomUUID();
        String header = "application/" + subtype;

        // when
        MediaType first = MediaType.parse(header);

        // then - the parse result is stored in the bounded cache (unless the cache happens to be full)
        Map<String, MediaType> cache = parseCache();
        assertThat(
            "a freshly parsed header must be cached unless the bounded cache is already full",
            cache.containsKey(header) || cache.size() >= maxCacheEntries(),
            is(true)
        );

        // and - when it is cached, a second parse returns the SAME instance (the hit path), never a re-parse
        if (cache.containsKey(header)) {
            assertThat(MediaType.parse(header), sameInstance(first));
        }
        // and - either way the value is correct
        assertThat(MediaType.parse(header), is(new MediaType("application", subtype)));
    }

    @Test
    public void shouldNotConflateCharsetCaseVariantsThroughCache() {
        // given - two headers that differ ONLY in charset case; keyed verbatim they are distinct cache
        // keys, but each must resolve to the same (correct) UTF-8 charset - never conflated, never lost
        MediaType lower = MediaType.parse("application/json; charset=utf-8");
        MediaType upper = MediaType.parse("application/json; charset=UTF-8");

        // then
        assertThat(lower.getCharset(), is(StandardCharsets.UTF_8));
        assertThat(upper.getCharset(), is(StandardCharsets.UTF_8));
        assertThat(lower, is(upper));
        // re-parsing (cache hit) does not drift
        assertThat(MediaType.parse("application/json; charset=UTF-8").getCharset(), is(StandardCharsets.UTF_8));
    }

    @Test
    public void shouldExposeUnmodifiableParametersFromParseWithParameter() {
        Map<String, String> parameters = MediaType.parse("text/html; charset=utf-8").getParameters();
        assertThrows(UnsupportedOperationException.class, () -> parameters.put("charset", "iso-8859-1"));
        assertThrows(UnsupportedOperationException.class, () -> parameters.remove("charset"));
    }

    @Test
    public void shouldExposeUnmodifiableParametersFromParseWithoutParameter() {
        Map<String, String> parameters = MediaType.parse("application/json").getParameters();
        assertThrows(UnsupportedOperationException.class, () -> parameters.put("charset", "utf-8"));
    }

    @Test
    public void shouldExposeUnmodifiableParametersFromConstructor() {
        Map<String, String> parameters = new MediaType("application", "json").getParameters();
        assertThrows(UnsupportedOperationException.class, () -> parameters.put("charset", "utf-8"));
    }

    @Test
    public void shouldBoundParseCacheAgainstAttackerControlledContentTypes() throws Exception {
        // given
        int max = maxCacheEntries();

        // when - parse far more DISTINCT headers than the bound (as a hostile client sending a unique
        // Content-Type per request would)
        for (int i = 0; i < (max * 2) + 100; i++) {
            MediaType.parse("application/x-bound-probe-" + i);
        }

        // then - the cache never grows past its hard bound, so it cannot be an unbounded memory sink
        assertThat(parseCache().size(), is(lessThanOrEqualTo(max)));

        // and - parsing still returns correct results for an uncached header while the cache is full
        assertThat(MediaType.parse("text/plain"), is(new MediaType("text", "plain")));
    }

}