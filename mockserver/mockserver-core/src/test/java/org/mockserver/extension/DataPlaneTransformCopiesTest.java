package org.mockserver.extension;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A response transformer is given a deep copy of the response, without its streaming body: nothing it does reaches
 * the response the server holds, a result equal to its input leaves the input itself to be written, and a changed
 * streamed head keeps the body it streams.
 */
public class DataPlaneTransformCopiesTest {

    private static final class Transformer implements ResponseTransformer {
        private final java.util.function.Function<HttpResponse, HttpResponse> transform;
        final AtomicReference<HttpResponse> given = new AtomicReference<>();

        Transformer(java.util.function.Function<HttpResponse, HttpResponse> transform) {
            this.transform = transform;
        }

        @Override
        public String name() {
            return "copies";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            given.set(response);
            return transform.apply(response);
        }
    }

    private static DataPlaneOutcome transform(HttpResponse response, boolean streamed, Transformer transformer) {
        TransformContext context = TransformContext.of(DataPlaneExtensionTestKit.filterContext().build(), streamed, null, false);
        return new DataPlaneExtensionTestKit(configuration()).transform(request("/copies"), response, context, transformer);
    }

    @Test
    public void shouldWriteTheInputItselfWhenNothingChanged() {
        HttpResponse original = response("body").withHeader("x-a", "1");
        Transformer identity = new Transformer(response -> response);

        DataPlaneOutcome outcome = transform(original, false, identity);

        assertThat(outcome.response(), sameInstance(original));
        assertThat(outcome.changedBy(), is(empty()));
        assertThat("the transformer is given a copy", identity.given.get(), not(sameInstance(original)));
    }

    @Test
    public void shouldNeverLetAnEditReachTheResponseTheServerHolds() {
        HttpResponse original = response("body").withHeader("x-a", "1");
        Transformer editor = new Transformer(response -> response.replaceHeader(header("x-a", "2")).withBody("edited"));

        DataPlaneOutcome outcome = transform(original, false, editor);

        assertThat(original.getFirstHeader("x-a"), is("1"));
        assertThat(original.getBodyAsString(), is("body"));
        assertThat(outcome.response().getFirstHeader("x-a"), is("2"));
        assertThat(outcome.response().getBodyAsString(), is("edited"));
        assertThat("the response written is not the object the extension still holds", outcome.response(), not(sameInstance(editor.given.get())));
        assertThat(outcome.changedBy(), contains("copies"));
    }

    @Test
    public void shouldShowAStreamedHeadWithoutItsBodyAndKeepTheBody() {
        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse original = response().withStatusCode(200).withHeader("x-a", "1").withStreamingBody(streamingBody);
        Transformer headEditor = new Transformer(response -> response.withHeader("x-b", "added"));

        DataPlaneOutcome outcome = transform(original, true, headEditor);

        assertThat(headEditor.given.get().getStreamingBody(), is(nullValue()));
        assertThat(outcome.response().getFirstHeader("x-b"), is("added"));
        assertThat(outcome.response().getStreamingBody(), sameInstance(streamingBody));
    }

    @Test
    public void shouldDropAnInheritedContentLengthWhenTheBodyChanges() {
        HttpResponse forwarded = response("abc").withHeader("content-length", "3");
        Transformer appender = new Transformer(response -> response.withBody(response.getBodyAsString() + "-TRANSFORMED"));

        DataPlaneOutcome outcome = transform(forwarded, false, appender);

        assertThat(outcome.response().getBodyAsString(), is("abc-TRANSFORMED"));
        assertThat("the encoder sets the length of the body sent", outcome.response().containsHeader("content-length"), is(false));
        assertThat(forwarded.getFirstHeader("content-length"), is("3"));
    }

    @Test
    public void shouldKeepAContentLengthTheTransformerSetItself() {
        HttpResponse forwarded = response("abc").withHeader("Content-Length", "3");
        Transformer setter = new Transformer(response -> response.withBody("abcd").replaceHeader(header("Content-Length", "4")));

        DataPlaneOutcome outcome = transform(forwarded, false, setter);

        assertThat(outcome.response().getFirstHeader("content-length"), is("4"));
    }

    @Test
    public void shouldKeepTheContentLengthWhenTheBodyIsUnchanged() {
        HttpResponse forwarded = response("abc").withHeader("content-length", "3");
        Transformer headerOnly = new Transformer(response -> response.withHeader("x-b", "added"));

        DataPlaneOutcome outcome = transform(forwarded, false, headerOnly);

        assertThat(outcome.response().getFirstHeader("x-b"), is("added"));
        assertThat(outcome.response().getFirstHeader("content-length"), is("3"));
    }
}
