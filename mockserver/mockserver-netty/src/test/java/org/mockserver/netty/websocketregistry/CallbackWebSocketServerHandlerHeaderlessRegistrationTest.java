package org.mockserver.netty.websocketregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.WebSocketClientIdDTO;
import org.mockserver.serialization.model.WebSocketMessageDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Tests that {@link WebSocketClientRegistry#registerClient(String, ChannelHandlerContext)}
 * always sends a {@link WebSocketClientIdDTO} containing the assigned clientId back to the
 * connecting client. This is the foundation for the "header-less registration" path used
 * by the dashboard UI, which (unlike language clients) cannot set custom headers on a
 * browser WebSocket upgrade request.
 *
 * <p>{@code CallbackWebSocketServerHandler} uses the client-supplied clientId when the header is
 * present and generates a UUID when it is absent; either way it sends the same DTO.
 * This test verifies the DTO write.
 */
public class CallbackWebSocketServerHandlerHeaderlessRegistrationTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    /**
     * Creates an EmbeddedChannel with a dummy handler so that
     * pipeline().firstContext() returns a valid ChannelHandlerContext.
     */
    private EmbeddedChannel createChannel() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }

    @Test
    public void registerClientSendsWebSocketClientIdDTO() throws Exception {
        // given
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger();
        WebSocketClientRegistry registry = new WebSocketClientRegistry(configuration, logger);

        EmbeddedChannel channel = createChannel();
        ChannelHandlerContext ctx = channel.pipeline().firstContext();

        String clientId = "test-header-less-client-id";

        // when
        registry.registerClient(clientId, ctx);

        // then - verify the channel received a TextWebSocketFrame with the DTO
        TextWebSocketFrame frame = channel.readOutbound();
        assertThat("Expected a TextWebSocketFrame to be written", frame, is(notNullValue()));

        String json = frame.text();
        WebSocketMessageDTO dto = objectMapper.readValue(json, WebSocketMessageDTO.class);

        assertThat(dto.getType(), is(WebSocketClientIdDTO.class.getName()));
        assertThat(dto.getValue(), is(notNullValue()));

        WebSocketClientIdDTO clientIdDTO = objectMapper.readValue(dto.getValue(), WebSocketClientIdDTO.class);
        assertThat(clientIdDTO.getClientId(), is(clientId));

        // verify the client is registered
        assertThat(registry.size(), is(1));

        frame.release();
        channel.close();
    }

    @Test
    public void registerClientWithServerGeneratedIdSendsCorrectDTO() throws Exception {
        // given - simulates the header-less case where the server generates the clientId
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger();
        WebSocketClientRegistry registry = new WebSocketClientRegistry(configuration, logger);

        EmbeddedChannel channel = createChannel();
        ChannelHandlerContext ctx = channel.pipeline().firstContext();

        // The server would call UUIDService.getUUID() in CallbackWebSocketServerHandler
        // to generate this ID when no CLIENT_REGISTRATION_ID_HEADER is present
        String serverGeneratedId = java.util.UUID.randomUUID().toString();

        // when
        registry.registerClient(serverGeneratedId, ctx);

        // then - verify the channel received a TextWebSocketFrame with the DTO
        TextWebSocketFrame frame = channel.readOutbound();
        assertThat("Expected a TextWebSocketFrame to be written", frame, is(notNullValue()));

        String json = frame.text();
        WebSocketMessageDTO dto = objectMapper.readValue(json, WebSocketMessageDTO.class);

        assertThat(dto.getType(), is(WebSocketClientIdDTO.class.getName()));

        WebSocketClientIdDTO clientIdDTO = objectMapper.readValue(dto.getValue(), WebSocketClientIdDTO.class);
        assertThat("Server-generated clientId should be echoed back",
            clientIdDTO.getClientId(), is(serverGeneratedId));

        // verify the client is registered
        assertThat(registry.size(), is(1));

        frame.release();
        channel.close();
    }

    @Test
    public void multipleClientsCanRegisterIndependently() throws Exception {
        // given
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger();
        WebSocketClientRegistry registry = new WebSocketClientRegistry(configuration, logger);

        EmbeddedChannel channel1 = createChannel();
        EmbeddedChannel channel2 = createChannel();

        // when - two clients register: one with explicit id (language client), one server-generated (dashboard)
        registry.registerClient("language-client-id", channel1.pipeline().firstContext());
        registry.registerClient(java.util.UUID.randomUUID().toString(), channel2.pipeline().firstContext());

        // then
        assertThat(registry.size(), is(2));

        // Verify both channels got their DTO
        TextWebSocketFrame frame1 = channel1.readOutbound();
        TextWebSocketFrame frame2 = channel2.readOutbound();
        assertThat(frame1, is(notNullValue()));
        assertThat(frame2, is(notNullValue()));

        WebSocketMessageDTO dto1 = objectMapper.readValue(frame1.text(), WebSocketMessageDTO.class);
        WebSocketClientIdDTO clientIdDTO1 = objectMapper.readValue(dto1.getValue(), WebSocketClientIdDTO.class);
        assertThat(clientIdDTO1.getClientId(), is("language-client-id"));

        frame1.release();
        frame2.release();
        channel1.close();
        channel2.close();
    }
}
