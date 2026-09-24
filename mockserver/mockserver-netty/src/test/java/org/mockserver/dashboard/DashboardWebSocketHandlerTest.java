package org.mockserver.dashboard;

import com.google.common.base.Joiner;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOGroupSerializer;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOSerializer;
import org.mockserver.dashboard.serializers.DescriptionProcessor;
import org.mockserver.dashboard.serializers.DescriptionSerializer;
import org.mockserver.dashboard.serializers.ThrowableSerializer;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.NO_MATCH_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.unification.PortUnificationHandler.http2Enabled;

public class DashboardWebSocketHandlerTest {

    // Every test that stands up dashboard infrastructure registers what it creates here so a single
    // @After can release it. Without this, each test leaks the MockServerEventLog disruptor consumer
    // thread and its query pool (both daemon, "MockServer-EventLog*"), plus the handler's own
    // throttle-refill and send executors (NON-daemon, "pool-N-thread-N"), for the life of the shared
    // surefire JVM. Those threads accumulate across the class and starve later tests in this module.
    private final List<Scheduler> trackedSchedulers = new ArrayList<>();
    private final List<HttpState> trackedHttpStates = new ArrayList<>();
    private final List<DashboardWebSocketHandler> trackedHandlers = new ArrayList<>();
    private final List<io.netty.channel.embedded.EmbeddedChannel> trackedChannels = new ArrayList<>();

    private Scheduler track(Scheduler scheduler) {
        trackedSchedulers.add(scheduler);
        return scheduler;
    }

    private HttpState track(HttpState httpState) {
        trackedHttpStates.add(httpState);
        return httpState;
    }

    private DashboardWebSocketHandler track(DashboardWebSocketHandler handler) {
        trackedHandlers.add(handler);
        return handler;
    }

    private <C extends io.netty.channel.embedded.EmbeddedChannel> C track(C channel) {
        trackedChannels.add(channel);
        return channel;
    }

    // The EmbeddedChannel tests all build the same async-scheduler-backed HttpState. Funnel that
    // through one tracked factory so both the Scheduler thread pools and the event log get torn down.
    private HttpState newAsyncHttpState() {
        Scheduler scheduler = track(new Scheduler(configuration(), new MockServerLogger()));
        return track(new HttpState(configuration(), new MockServerLogger(), scheduler));
    }

    @After
    public void stopDashboardInfrastructure() {
        // Order matters only in that we silence the handler's refill/send executors before stopping
        // the event log, so no in-flight throttled send races the disruptor shutdown. Each teardown is
        // guarded so one failure cannot suppress the rest.
        for (DashboardWebSocketHandler handler : trackedHandlers) {
            stopHandlerExecutors(handler);
        }
        for (io.netty.channel.embedded.EmbeddedChannel channel : trackedChannels) {
            try {
                // For pipeline-registered handlers this drives production handlerRemoved (which shuts
                // the handler's executors) and releases any captured frames' buffers.
                channel.finishAndReleaseAll();
            } catch (Throwable ignore) {
                // a bare EmbeddedChannel with no inbound/outbound may throw nothing; ignore either way
            }
        }
        for (HttpState httpState : trackedHttpStates) {
            try {
                // HttpState.stop() shuts the MockServerEventLog disruptor and its query pool — the same
                // call production uses, mirroring MockServerEventLogConcurrencyTest's teardown.
                httpState.stop();
            } catch (Throwable ignore) {
                // best-effort: never let one test's cleanup failure mask another
            }
        }
        for (Scheduler scheduler : trackedSchedulers) {
            try {
                scheduler.shutdown();
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        trackedHandlers.clear();
        trackedChannels.clear();
        trackedHttpStates.clear();
        trackedSchedulers.clear();
    }

    // Shut the handler's two owned executors, mirroring production DashboardWebSocketHandler.handlerRemoved
    // (scheduler.shutdown(), throttleExecutorService.shutdownNow()). Standalone handlers in the helper-based
    // tests are never added to a channel pipeline, so handlerRemoved never fires for them; we reach the
    // private fields by reflection, the same idiom this test already uses in expectationUpdateItemLimit().
    private static void stopHandlerExecutors(DashboardWebSocketHandler handler) {
        shutdownExecutorField(handler, "scheduler", false);
        shutdownExecutorField(handler, "throttleExecutorService", true);
    }

    private static void shutdownExecutorField(DashboardWebSocketHandler handler, String fieldName, boolean now) {
        try {
            java.lang.reflect.Field field = DashboardWebSocketHandler.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(handler);
            if (value instanceof java.util.concurrent.ExecutorService) {
                java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) value;
                if (now) {
                    executor.shutdownNow();
                } else {
                    executor.shutdown();
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not shut down DashboardWebSocketHandler." + fieldName, e);
        }
    }

    @Test
    public void shouldSerialiseEventsAndIgnoreDeletedLogEvents() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo")
                .setDeleted(true),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat")
                .setDeleted(true),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat")
                .setDeleted(true),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne")
                .setDeleted(true),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo")
                .setDeleted(true),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree")
                .setDeleted(true)
        );
        String renderedList = "{\n" +
            "  \"logMessages\" : [ {\n" +
            "    \"key\" : \"" + logEntries.get(10).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(10).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(10).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(10).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatThree\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(8).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(8).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(8).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(8).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatTwo\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(6).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(6).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(6).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(6).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatOne\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(4).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(4).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(4).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\",\n" +
            "        \"value\" : \"messagePartOne:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentOne\\\"\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\",\n" +
            "        \"value\" : \"messagePartTwo:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentTwo\\\"\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  } ],\n" +
            "  \"activeExpectationsTotal\" : 0,\n" +
            "  \"activeExpectationsIncludeLlm\" : false,\n" +
            "  \"recordedRequests\" : [ {\n" +
            "    \"description\" : \"  /somePathTwo\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathTwo\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(10).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(10).getTimestamp() + "\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(8).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(8).getTimestamp() + "\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(6).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(6).getTimestamp() + "\"\n" +
            "  } ]\n" +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithNoRequestFilter() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree")
        );
        String renderedList = "{\n" +
            "  \"logMessages\" : [ {\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(5).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(5).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(5).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatThree\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(4).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(4).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(4).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatTwo\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatOne\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\",\n" +
            "        \"value\" : \"messagePartOne:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentOne\\\"\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\",\n" +
            "        \"value\" : \"messagePartTwo:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentTwo\\\"\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  } ],\n" +
            "  \"activeExpectationsTotal\" : 0,\n" +
            "  \"activeExpectationsIncludeLlm\" : false,\n" +
            "  \"recordedRequests\" : [ {\n" +
            "    \"description\" : \"  /somePathTwo\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathTwo\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(5).getTimestamp() + "\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(4).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(3).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"\n" +
            "  } ]\n" +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithRequestFilter() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree")
        );
        String renderedList = "{\n" +
            "  \"logMessages\" : [ {\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(5).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(5).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(5).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatThree\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  } ],\n" +
            "  \"activeExpectationsTotal\" : 0,\n" +
            "  \"activeExpectationsIncludeLlm\" : false,\n" +
            "  \"recordedRequests\" : [ {\n" +
            "    \"description\" : \"  /somePathTwo\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathTwo\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_request\",\n" +
            "    \"timestamp\" : \"" + logEntries.get(5).getTimestamp() + "\"\n" +
            "  } ]\n" +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request("/somePathTwo"), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseExpectationsWithRequestFilter() throws InterruptedException {
        // given
        List<Expectation> expectations = Arrays.asList(
            new Expectation(request("one")).thenRespond(response("one")),
            new Expectation(request("two")).thenRespond(response("two")),
            new Expectation(request("three")).thenRespond(response("three"))
        );
        String renderedList = "" +
            "  \"activeExpectations\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(0).getId() + ":   one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 1," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request("one"), Collections.emptyList(), expectations, renderedList);
    }

    @Test
    public void shouldSerialiseMessageWithException() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setMessageFormat("messageFormat")
                .setThrowable(new RuntimeException("TEST EXCEPTION"))
        );
        String[] renderedList = new String[]{
            "{" + NEW_LINE +
                "  \"logMessages\" : [ {" + NEW_LINE +
                "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
                "    \"value\" : {" + NEW_LINE +
                "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
                "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
                "      \"style\" : {" + NEW_LINE +
                "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
                "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
                "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
                "        \"overflow\" : \"auto\"," + NEW_LINE +
                "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
                "        \"paddingTop\" : \"4px\"" + NEW_LINE +
                "      }," + NEW_LINE +
                "      \"messageParts\" : [ {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
                "        \"value\" : \"messageFormat\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(1).id() + "_throwable_msg\"," + NEW_LINE +
                "        \"value\" : \"exception:\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(1).id() + "_throwable_value\"," + NEW_LINE +
                "        \"multiline\" : true," + NEW_LINE +
                "        \"argument\" : true," + NEW_LINE +
                "        \"value\" : [ \"java.lang.RuntimeException: TEST EXCEPTION\", \"\\tat org.mockserver.dashboard.DashboardWebSocketHandlerTest.shouldSerialiseMessageWithException",
            "      } ]" + NEW_LINE +
                "    }" + NEW_LINE +
                "  }, {" + NEW_LINE +
                "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
                "    \"value\" : {" + NEW_LINE +
                "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
                "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
                "      \"style\" : {" + NEW_LINE +
                "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
                "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
                "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
                "        \"overflow\" : \"auto\"," + NEW_LINE +
                "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
                "        \"paddingTop\" : \"4px\"" + NEW_LINE +
                "      }," + NEW_LINE +
                "      \"messageParts\" : [ {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
                "        \"value\" : \"messagePartOne:\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\"," + NEW_LINE +
                "        \"multiline\" : false," + NEW_LINE +
                "        \"argument\" : true," + NEW_LINE +
                "        \"value\" : \"\\\"argumentOne\\\"\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\"," + NEW_LINE +
                "        \"value\" : \"messagePartTwo:\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\"," + NEW_LINE +
                "        \"multiline\" : false," + NEW_LINE +
                "        \"argument\" : true," + NEW_LINE +
                "        \"value\" : \"\\\"argumentTwo\\\"\"" + NEW_LINE +
                "      } ]" + NEW_LINE +
                "    }" + NEW_LINE +
                "  } ]," + NEW_LINE +
                "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
                "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
                "}"};

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithRequest() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("two"))
                .setMessageFormat("messageFormat")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormat\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messagePartOne:\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\"," + NEW_LINE +
            "        \"multiline\" : false," + NEW_LINE +
            "        \"argument\" : true," + NEW_LINE +
            "        \"value\" : \"\\\"argumentOne\\\"\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\"," + NEW_LINE +
            "        \"value\" : \"messagePartTwo:\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\"," + NEW_LINE +
            "        \"multiline\" : false," + NEW_LINE +
            "        \"argument\" : true," + NEW_LINE +
            "        \"value\" : \"\\\"argumentTwo\\\"\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRollUpEventsWithCorrelationId() throws InterruptedException {
        // given
        String logCorrelationId = UUIDService.getUUID();
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one").withLogCorrelationId(logCorrelationId))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("two").withLogCorrelationId(logCorrelationId))
                .setMessageFormat("messageFormat")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log_group\"," + NEW_LINE +
            "    \"group\" : {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"value\" : [ {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messageFormat\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }, {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messagePartOne:\"" + NEW_LINE +
            "        }, {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_0arg\"," + NEW_LINE +
            "          \"multiline\" : false," + NEW_LINE +
            "          \"argument\" : true," + NEW_LINE +
            "          \"value\" : \"\\\"argumentOne\\\"\"" + NEW_LINE +
            "        }, {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_1msg\"," + NEW_LINE +
            "          \"value\" : \"messagePartTwo:\"" + NEW_LINE +
            "        }, {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_1arg\"," + NEW_LINE +
            "          \"multiline\" : false," + NEW_LINE +
            "          \"argument\" : true," + NEW_LINE +
            "          \"value\" : \"\\\"argumentTwo\\\"\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    } ]" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRollUpEventsWithSameCorrelationIdAndNotWarpEventsWithUniqueCorrelationId() throws InterruptedException {
        // given
        String logCorrelationIdShared = UUIDService.getUUID();
        String logCorrelationIdOne = UUIDService.getUUID();
        String logCorrelationIdTwo = UUIDService.getUUID();
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one").withLogCorrelationId(logCorrelationIdShared))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setHttpRequest(request("two").withLogCorrelationId(logCorrelationIdShared))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setHttpRequest(request("three").withLogCorrelationId(logCorrelationIdOne))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setHttpRequest(request("four").withLogCorrelationId(logCorrelationIdTwo))
                .setMessageFormat("messageFormatFour")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatFour\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log_group\"," + NEW_LINE +
            "    \"group\" : {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"value\" : [ {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }, {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    } ]" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithoutFields() throws InterruptedException {
        // given
        RuntimeException throwable = new RuntimeException();
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one").withLogCorrelationId(UUIDService.getUUID())),
            new LogEntry()
                .setHttpRequest(request("two")),
            new LogEntry()
                .setMessageFormat("messageFormatTwo"),
            new LogEntry(),
            new LogEntry()
                .setThrowable(throwable)
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(4).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(4).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(4).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"RuntimeException\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(4).id() + "_throwable_msg\"," + NEW_LINE +
            "        \"value\" : \"exception:\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(4).id() + "_throwable_value\"," + NEW_LINE +
            "        \"multiline\" : true," + NEW_LINE +
            "        \"argument\" : true," + NEW_LINE +
            "        \"value\" : [ " + Joiner.on(", ").join(Arrays.stream(getStackTrace(throwable).split(System.lineSeparator())).map(line -> "\"" + line.replaceAll("\\t", "\\\\t") + "\"").collect(Collectors.toList())) + " ]" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRecordedRequests() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("one"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("two"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("three"))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("four"))
                .setMessageFormat("messageFormatFour")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatFour\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false," + NEW_LINE +
            "  \"recordedRequests\" : [ {" + NEW_LINE +
            "    \"description\" : \"   four\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"four\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_request\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"  three\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"three\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_request\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_request\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_request\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRecordedRequestsEventsWithoutFields() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("two")),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false," + NEW_LINE +
            "  \"recordedRequests\" : [ {" + NEW_LINE +
            "    \"description\" : \"  two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_request\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldPairRecordedRequestWithMatchingResponseByCorrelationId() throws InterruptedException {
        // given — three lifecycles, all distinguished by correlationId:
        //   corr-A: RECEIVED_REQUEST paired with EXPECTATION_RESPONSE (mock match)
        //   corr-B: RECEIVED_REQUEST paired with NO_MATCH_RESPONSE (404 path)
        //   corr-C: RECEIVED_REQUEST with no matching response (response not yet logged)
        // The reverse-chronological stream surfaces each response BEFORE its
        // own request, so DashboardWebSocketHandler can stash responses by
        // correlationId in a single pass and look them up when the matching
        // request is processed.
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/matched"))
                .setCorrelationId("corr-A"),
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setHttpResponse(response().withStatusCode(200).withBody("matched-body"))
                .setCorrelationId("corr-A"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/unmatched"))
                .setCorrelationId("corr-B"),
            new LogEntry()
                .setType(NO_MATCH_RESPONSE)
                .setHttpResponse(response().withStatusCode(404).withBody("not-found-body"))
                .setCorrelationId("corr-B"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/orphan"))
                .setCorrelationId("corr-C")
        );

        // The matched request must carry the paired EXPECTATION_RESPONSE body.
        String matchedFragment =
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"/matched\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"body\" : \"matched-body\"" + NEW_LINE +
            "      }";

        // The unmatched request must carry the paired NO_MATCH_RESPONSE body.
        String unmatchedFragment =
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"/unmatched\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 404," + NEW_LINE +
            "        \"body\" : \"not-found-body\"" + NEW_LINE +
            "      }";

        // The orphan request must emit { httpRequest } only — no httpResponse
        // key — proving the pairing degrades gracefully when the response log
        // entry is missing.
        String orphanFragment =
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"/orphan\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }";

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request(), logEntries, Collections.emptyList(),
            matchedFragment, unmatchedFragment, orphanFragment);
    }

    @Test
    public void shouldSerialiseForwardedRequests() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("one"))
                .setHttpResponse(response("one"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("two"))
                .setHttpResponse(response("two"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("three"))
                .setHttpResponse(response("three"))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("four"))
                .setHttpResponse(response("four"))
                .setMessageFormat("messageFormatFour")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatFour\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false," + NEW_LINE +
            "  \"proxiedRequests\" : [ {" + NEW_LINE +
            "    \"description\" : \"   four\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"four\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"four\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_proxied\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"  three\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"three\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"three\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_proxied\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_proxied\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"one\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_proxied\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseForwardedRequestsForEventsWithoutFields() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("one"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpResponse(response("two"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 0," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false," + NEW_LINE +
            "  \"proxiedRequests\" : [ {" + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_proxied\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"  one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_proxied\"," + NEW_LINE +
            "    \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseExpectations() throws InterruptedException {
        // given
        List<Expectation> expectations = Arrays.asList(
            new Expectation(request("one")).thenRespond(response("one")),
            new Expectation(request("two")).thenRespond(response("two")),
            new Expectation(request("three")).thenRespond(response("three"))
        );
        String renderedList = "" +
            "  \"activeExpectations\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(0).getId() + ":     one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(1).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(1).getId() + ":     two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"two\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(1).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(2).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(2).getId() + ":   three\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"three\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"three\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(2).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"activeExpectationsTotal\" : 3," + NEW_LINE +
            "  \"activeExpectationsIncludeLlm\" : false" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request(), Collections.emptyList(), expectations, renderedList);
    }

    // ---------------------------------------------------------------------------------------------
    // Option 6: the dashboard caches the (expensive) ExpectationDTO -> JSON serialisation and reuses
    // it while the expectation is unchanged. Two properties are proved:
    //   (1) THE WIN, race-immune: once the cache is warm, repeatedly updating an UNCHANGED set
    //       re-serialises nothing (serialisation-count delta == 0), and the emitted JSON is identical.
    //   (2) THE SAFETY, conservativeness: an added / edited / removed / Times-consumed expectation is
    //       reflected in the emitted JSON — the cache never shows a stale expectation. It is backed by
    //       a count assertion that the change DID cause a re-serialisation.
    // The observable signal is activeExpectationSerialisationCountForTesting(): it counts only genuine
    // (cache-miss) serialisations. Absolute totals are NOT asserted because, on a COLD cache, two
    // concurrent initial update passes can each populate it once (a harmless, bounded startup race);
    // the tests therefore quiesce first, then assert DELTAS, which are unaffected by that race because
    // a warm cache yields hits on every thread.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotReserialiseUnchangedActiveExpectations() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when the dashboard is updated repeatedly with nothing changed
        String first = awaitFrame(fixture, request());
        String second = awaitFrame(fixture, request());
        String third = awaitFrame(fixture, request());

        // then nothing is re-serialised and every frame is byte-identical
        assertThat("no re-serialisation for an unchanged set", fixture.handler.activeExpectationSerialisationCountForTesting(), is(warm));
        assertThat(second, is(first));
        assertThat(third, is(first));
    }

    @Test
    public void shouldReserialiseOnlyTheAddedExpectation() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when a fourth expectation is added
        fixture.requestMatchers.add(new Expectation(request("four")).withId("id-four").thenRespond(response("four")), MockServerMatcherNotifier.Cause.API);
        long afterAdd = quiesce(fixture.handler);

        // then the new expectation appears, and ONLY the added expectation is (re)serialised: the
        // three pre-existing expectations are reused from the warm cache.
        //
        // The delta is 1 OR 2, not exactly 1, and both are correct. An add fires TWO independent
        // dashboard push triggers: the matcher-changed notifier (RequestMatchers.notifyListeners)
        // AND -- when INFO logging is enabled -- the event-log notifier for the CREATED_EXPECTATION
        // entry the add records. Each drives its own sendUpdate, and the per-expectation JSON is
        // built OUTSIDE the send throttle's semaphore (the semaphore gates only the network write),
        // so the two passes can race: if the second reads activeExpectationJsonCache before the first
        // has stored id-four's tree, both serialise id-four once (delta 2); if the first stored
        // first, the second is a cache hit (delta 1). This is the documented, harmless "cold-start
        // double-fire" race -- the two trees for id-four are byte-identical (same reference, same
        // remainingTimes) and only the throttled write is user-visible -- so the fix is to assert the
        // property that actually matters rather than a race-sensitive exact count. The property: the
        // added expectation is serialised at most once PER TRIGGER (so at most 2) while the three
        // pre-existing ones are reused. If the warm cache were NOT reused, every pass would
        // re-serialise all four expectations, making the delta >= 4 -- so the <= 2 bound still fails
        // loudly on a broken cache.
        String frame = awaitFrame(fixture, request());
        assertThat(activeExpectations(frame), containsString("id-four"));
        long delta = afterAdd - warm;
        assertThat("adding one expectation reuses the warm set and serialises only the new id"
                + " (1 or, under the two-trigger race, 2 -- never the reused set, which would be >= 4): delta=" + delta,
            delta >= 1 && delta <= 2, is(true));
    }

    @Test
    public void shouldReserialiseEditedExpectationAndReflectNewContent() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("original-two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when the middle expectation is edited in place (same id, changed response body)
        fixture.requestMatchers.add(new Expectation(request("two")).withId("id-two").thenRespond(response("edited-two")), MockServerMatcherNotifier.Cause.API);
        long afterEdit = quiesce(fixture.handler);

        // then the NEW body is shown (never the stale cached one), and ONLY the edited expectation is
        // re-serialised -- id-one and id-three are reused from the warm cache.
        String frame = awaitFrame(fixture, request());
        assertThat(activeExpectations(frame), containsString("edited-two"));
        assertThat(activeExpectations(frame), not(containsString("original-two")));
        // Delta 1 OR 2: an in-place edit is an upsert, which fires the matcher-changed notifier and --
        // when INFO logging is enabled -- the UPDATED_EXPECTATION event-log notifier. Each drives a
        // sendUpdate that must re-serialise id-two (its Expectation reference changed, so the cache
        // entry is invalidated); the two passes race on repopulating the cache exactly as the added-
        // expectation test documents, so id-two is serialised once or twice (byte-identically). The
        // reused pair (id-one, id-three) is NEVER re-serialised, so a broken warm cache would re-
        // serialise all three per pass and push the delta to >= 3 -- the <= 2 bound still catches it.
        long delta = afterEdit - warm;
        assertThat("editing one expectation reuses the warm pair and re-serialises only the edited id"
                + " (1 or, under the two-trigger race, 2 -- never the reused pair, which would be >= 3): delta=" + delta,
            delta >= 1 && delta <= 2, is(true));
    }

    @Test
    public void shouldNotReserialiseWhenExpectationRemoved() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when one expectation is removed
        fixture.requestMatchers.clear(request("two"));
        long afterRemove = quiesce(fixture.handler);

        // then the removed expectation is gone, the survivors remain, and nothing was re-serialised
        String frame = awaitFrame(fixture, request());
        assertThat(activeExpectations(frame), containsString("id-one"));
        assertThat(activeExpectations(frame), containsString("id-three"));
        assertThat(activeExpectations(frame), not(containsString("id-two")));
        assertThat("removal re-serialises nothing", afterRemove - warm, is(0L));
    }

    @Test
    public void shouldReserialiseWhenTimesConsumedOnServingPath() throws InterruptedException {
        // given one limited-Times expectation, warm in the cache with remainingTimes == 2
        Fixture fixture = newFixture(Collections.singletonList(
            new Expectation(request("once"), Times.exactly(2), TimeToLive.unlimited(), 0).withId("id-once").thenRespond(response("body"))
        ));
        long warm = quiesce(fixture.handler);
        assertThat(activeExpectations(awaitFrame(fixture, request())), containsString("\"remainingTimes\" : 2"));

        // when the SERVING path consumes one match (remainingTimes 2 -> 1). This is the trap: the
        // control-plane modification counter does not move, but the serialised form did.
        fixture.requestMatchers.firstMatchingExpectation(request("once"));
        long afterConsume = quiesce(fixture.handler);

        // then the dashboard shows the NEW remaining count, not the stale cached "2", and the
        // expectation was re-serialised. The staleness guard is the pair of containsString assertions
        // over activeExpectations (the live state shows 1, never the cached 2); the count assertion
        // only confirms a re-serialisation DID happen (delta >= 1) rather than the stale tree being
        // served untouched (delta 0). The upper bound tolerates the same two-trigger race as the
        // added/edited tests: under INFO logging the EXPECTATION_MATCHED event-log notifier fires a
        // second sendUpdate alongside the serving-path refresh, so the single expectation can be re-
        // serialised twice (byte-identically). With only one expectation the count cannot itself
        // distinguish a broken cache -- that is precisely what the remainingTimes containsString
        // assertions above are for.
        String frame = awaitFrame(fixture, request());
        assertThat(activeExpectations(frame), containsString("\"remainingTimes\" : 1"));
        assertThat(activeExpectations(frame), not(containsString("\"remainingTimes\" : 2")));
        long delta = afterConsume - warm;
        assertThat("consuming Times re-serialises the expectation at least once"
                + " (1, or 2 under the two-trigger race): delta=" + delta,
            delta >= 1 && delta <= 2, is(true));
    }

    @Test
    public void shouldCapActiveExpectationsAtLimitAndCacheThem() throws InterruptedException {
        // given 150 expectations (above the 100 UI_UPDATE_ITEM_LIMIT)
        List<Expectation> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new Expectation(request("/path" + i)).withId(String.format("id-%03d", i)).thenRespond(response("body" + i)));
        }
        Fixture fixture = newFixture(many);
        long warm = quiesce(fixture.handler);

        // then only the capped number of expectations is emitted, and a second unchanged update
        // re-serialises none of them (the cap is applied before serialisation, and the cached trees
        // are reused across updates)
        String frame = awaitFrame(fixture, request());
        assertThat("activeExpectations capped at UI_UPDATE_ITEM_LIMIT", countOccurrences(frame, "\"key\" : \"id-"), is(100));
        awaitFrame(fixture, request());
        assertThat("a warm capped set re-serialises nothing", fixture.handler.activeExpectationSerialisationCountForTesting(), is(warm));
    }

    @Test
    public void shouldReportTrueActiveExpectationsTotalAboveTheCap() throws Exception {
        // The list the dashboard renders is capped at EXPECTATION_UPDATE_ITEM_LIMIT, but the
        // activeExpectationsTotal carried alongside it must be the TRUE number of matchers the server
        // holds. Below the cap the two are trivially equal and prove nothing; the boundary is the whole
        // point, so register MORE than the limit and assert the list is capped while the total is the
        // real registered count -- NOT pinned at the cap. The limit is read from the class so this test
        // crosses whatever the real boundary is and cannot silently drift if the limit changes.
        int limit = expectationUpdateItemLimit();
        int registered = limit + 37; // safely above the cap
        List<Expectation> many = new java.util.ArrayList<>();
        for (int i = 0; i < registered; i++) {
            many.add(new Expectation(request("/path" + i)).withId(String.format("id-%04d", i)).thenRespond(response("body" + i)));
        }
        Fixture fixture = newFixture(many);
        quiesce(fixture.handler);

        String frame = awaitFrame(fixture, request());
        JsonNode tree = ObjectMapperFactory.createObjectMapper().readTree(frame);

        assertThat("the rendered activeExpectations list is capped at EXPECTATION_UPDATE_ITEM_LIMIT",
            tree.get("activeExpectations").size(), is(limit));
        assertThat("activeExpectationsTotal is the true registered count",
            tree.get("activeExpectationsTotal").asInt(), is(registered));
        assertThat("activeExpectationsTotal is emphatically NOT the cap",
            tree.get("activeExpectationsTotal").asInt(), is(not(limit)));
    }

    @Test
    public void shouldReportLlmExpectationPresentWhenItSitsBeyondTheCap() throws Exception {
        // THE BOUNDARY IS THE DEFECT. The dashboard renders only the first EXPECTATION_UPDATE_ITEM_LIMIT
        // expectations, so a boolean derived from that PAGE is a boolean about the page, not the server.
        // Register MORE than the limit of NON-LLM expectations FIRST and a single LLM expectation LAST,
        // so the LLM one is provably pushed off the page: below the cap the page would contain everything
        // and a naive page check would agree with the flag, proving nothing. The limit is read from the
        // class so this crosses whatever the real boundary is.
        int limit = expectationUpdateItemLimit();
        List<Expectation> many = new java.util.ArrayList<>();
        for (int i = 0; i < limit + 5; i++) {
            many.add(new Expectation(request("/path" + i)).withId(String.format("id-%04d", i)).thenRespond(response("body" + i)));
        }
        // The one LLM expectation, added AFTER the cap's worth of non-LLM ones, so it never appears on
        // the rendered page. An LLM expectation is one carrying an httpLlmResponse action.
        many.add(new Expectation(request("/llm")).withId("id-llm")
            .thenRespondWithLlm(org.mockserver.model.HttpLlmResponse.llmResponse().withProvider(org.mockserver.model.Provider.OPENAI)));
        Fixture fixture = newFixture(many);
        quiesce(fixture.handler);

        String frame = awaitFrame(fixture, request());
        JsonNode tree = ObjectMapperFactory.createObjectMapper().readTree(frame);

        // The LLM expectation is genuinely absent from the page the dashboard was sent...
        assertThat("the rendered page is capped at EXPECTATION_UPDATE_ITEM_LIMIT",
            tree.get("activeExpectations").size(), is(limit));
        // Scope the absence check to the rendered activeExpectations page, NOT the raw frame. The frame
        // also carries a logMessages history, and when the (global, shared-JVM) log level is INFO the
        // seeding of these expectations records a CREATED_EXPECTATION event that legitimately embeds the
        // expectation id -- so a raw-frame containsString("id-llm") trips on that history rather than on
        // the page, and does so ONLY under INFO logging. That is exactly what made this pass in an
        // isolated -Dtest= run (default ERROR level, no such events) and fail in the full-suite JVM (a
        // prior test leaves the global level at INFO). The property under test is "the LLM expectation
        // is not on the PAGE", which is deterministic regardless of log level. See the activeExpectations(frame)
        // helper below, which documents the same raw-frame-vs-page hazard.
        assertThat("the LLM expectation is NOT on the page (it sits beyond the cap)",
            tree.get("activeExpectations").toString(), not(containsString("id-llm")));
        assertThat("no LLM action is visible anywhere on the page",
            tree.get("activeExpectations").toString(), not(containsString("httpLlmResponse")));
        // ...yet the server-side flag still reports it, which a page-only check could never do.
        assertThat("activeExpectationsIncludeLlm is derived from the WHOLE server-side set, not the page",
            tree.get("activeExpectationsIncludeLlm").asBoolean(), is(true));
    }

    @Test
    public void shouldReportNoLlmExpectationWhenServerHoldsNone() throws Exception {
        // The negative pole: a server with only non-LLM expectations reports the flag false, so the UI
        // fallback (its latched page check) governs rather than a spurious server-side true.
        List<Expectation> many = new java.util.ArrayList<>();
        for (int i = 0; i < expectationUpdateItemLimit() + 5; i++) {
            many.add(new Expectation(request("/path" + i)).withId(String.format("id-%04d", i)).thenRespond(response("body" + i)));
        }
        Fixture fixture = newFixture(many);
        quiesce(fixture.handler);

        String frame = awaitFrame(fixture, request());
        JsonNode tree = ObjectMapperFactory.createObjectMapper().readTree(frame);
        assertThat("activeExpectationsIncludeLlm is false when no expectation is an LLM expectation",
            tree.get("activeExpectationsIncludeLlm").asBoolean(), is(false));
    }

    // Read the (private) cap straight from the class so the boundary-crossing test above tracks the
    // real limit rather than a hard-coded 100 that would silently drift out of step with production.
    private static int expectationUpdateItemLimit() throws Exception {
        java.lang.reflect.Field field = DashboardWebSocketHandler.class.getDeclaredField("EXPECTATION_UPDATE_ITEM_LIMIT");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    // Minimal fixture: seed expectations BEFORE the handler is registered (so seeding does not itself
    // serialise), then register a single live-view connection.
    private static final class Fixture {
        private final RequestMatchers requestMatchers;
        private final DashboardWebSocketHandler handler;
        private final MockChannelHandlerContext ctx;

        private Fixture(RequestMatchers requestMatchers, DashboardWebSocketHandler handler, MockChannelHandlerContext ctx) {
            this.requestMatchers = requestMatchers;
            this.handler = handler;
            this.ctx = ctx;
        }
    }

    private Fixture newFixture(List<Expectation> initial) {
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketHandlerTest.class);
        Scheduler scheduler = track(new Scheduler(configuration(), mockServerLogger, true));
        HttpState httpState = track(new HttpState(configuration(), mockServerLogger, scheduler));
        RequestMatchers requestMatchers = httpState.getRequestMatchers();
        if (!initial.isEmpty()) {
            requestMatchers.update(initial.toArray(new Expectation[0]), MockServerMatcherNotifier.Cause.API);
        }
        DashboardWebSocketHandler handler = track(new DashboardWebSocketHandler(httpState, false, true)).registerListeners();
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());
        handler.getClientRegistry().put(ctx, request());
        return new Fixture(requestMatchers, handler, ctx);
    }

    // Drive updates until the serialisation counter stops moving for a settle window, then return the
    // settled value. This warms the cache and absorbs the cold-start double-fire race, so that a
    // subsequent DELTA measures only what the operation under test caused.
    private long quiesce(DashboardWebSocketHandler handler) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        long previous = -1;
        while (System.currentTimeMillis() < deadline) {
            long current = handler.activeExpectationSerialisationCountForTesting();
            if (current == previous) {
                return current;
            }
            previous = current;
            Thread.sleep(700);
        }
        throw new AssertionError("serialisation count did not settle");
    }

    // Drive one update and return the resulting frame text. The dashboard throttles sends to roughly
    // one per second, so retry until a fresh frame is produced.
    private String awaitFrame(Fixture fixture, RequestDefinition filter) throws InterruptedException {
        fixture.ctx.textWebSocketFrame = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            fixture.handler.sendUpdate(fixture.ctx, filter);
            Thread.sleep(400);
            if (fixture.ctx.textWebSocketFrame != null) {
                return fixture.ctx.textWebSocketFrame.text();
            }
        }
        throw new AssertionError("no dashboard frame produced within timeout");
    }

    // Scope a cache assertion to the "activeExpectations" subtree -- the CURRENT expectation state the
    // ExpectationDTO->JSON cache under test serves -- rather than the raw frame text. Every frame also
    // carries a "logMessages" array of historical event snapshots (CREATED_EXPECTATION /
    // UPDATED_EXPECTATION / CLEARED / REMOVED_EXPECTATION / EXPECTATION_MATCHED), and those snapshots
    // legitimately embed an expectation's id, body, or remaining Times AT THE TIME THE EVENT WAS
    // LOGGED. When INFO logging is enabled a raw-frame containsString therefore sees an id or body that
    // lingers only in that history -- nothing to do with the cache -- which is exactly why the raw
    // assertions were broken on arrival (they only passed at the default ERROR log level, where the
    // events are never recorded). Parsing the frame and matching only activeExpectations tests the real
    // property: the cache serves the live state and never a stale cached tree. The subtree is
    // re-serialised with the DEFAULT pretty printer so the field/value separator stays " : ", matching
    // the pretty-printed needles (the handler under test is built with prettyPrint=true). Because the
    // subtree is taken from the frame the handler actually produced, a broken cache (serving a stale
    // tree) still surfaces here and fails the assertion -- the scoping narrows WHERE we look, it does
    // not make the assertion vacuous (the positive containsString checks in each test also prove the
    // subtree is non-empty).
    private static String activeExpectations(String frame) {
        try {
            ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            JsonNode activeExpectations = objectMapper.readTree(frame).get("activeExpectations");
            assertThat("frame carries an activeExpectations array", activeExpectations, is(notNullValue()));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(activeExpectations);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("dashboard frame was not valid JSON: " + frame, e);
        }
    }

    private void shouldRenderFilteredLogEntriesCorrectly(boolean contains, RequestDefinition requestFilter, List<LogEntry> logEntries, List<Expectation> expectations, String... renderListSections) throws InterruptedException {
        // given
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketHandlerTest.class);
        Scheduler scheduler = track(new Scheduler(configuration(), mockServerLogger, true));
        HttpState httpState = track(new HttpState(configuration(), mockServerLogger, scheduler));
        new Scheduler.SchedulerThreadFactory("MockServer Test " + this.getClass().getSimpleName()).newThread(() -> {
            MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
            for (LogEntry logEntry : logEntries) {
                mockServerEventLog.add(logEntry);
            }
            RequestMatchers requestMatchers = httpState.getRequestMatchers();
            if (!expectations.isEmpty()) {
                requestMatchers.update(expectations.toArray(new Expectation[0]), MockServerMatcherNotifier.Cause.API);
            }
        }).start();
        SECONDS.sleep(1);
        DashboardWebSocketHandler handler =
            track(new DashboardWebSocketHandler(httpState, false, true))
                .registerListeners();
        MockChannelHandlerContext mockChannelHandlerContext = track(new MockChannelHandlerContext());
        handler.getClientRegistry().put(mockChannelHandlerContext, request());

        // when
        handler.sendUpdate(mockChannelHandlerContext, requestFilter);
        SECONDS.sleep(1);

        // then
        TextWebSocketFrame textWebSocketFrame = mockChannelHandlerContext.textWebSocketFrame;
        for (String renderListSection : renderListSections) {
            assertThat(textWebSocketFrame.text(), contains ? containsString(renderListSection) : is(renderListSection));
        }
    }

    @Test
    public void shouldRejectWebSocketUpgradeOverHttp2() {
        // given
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, true, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));
        http2Enabled(channel);
        DefaultFullHttpRequest upgradeRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/_mockserver_ui_websocket"
        );

        // when
        channel.writeInbound(upgradeRequest);

        // then
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(501));
    }

    private static DefaultFullHttpRequest webSocketUpgradeRequest() {
        return webSocketUpgradeRequest("/_mockserver_ui_websocket");
    }

    @Test
    public void shouldRejectWebSocketUpgradeWhenControlPlaneAuthEnabledAndNotAuthenticated() {
        // given - control-plane auth configured but the upgrade carries no/invalid credentials
        HttpState httpState = newAsyncHttpState();
        httpState.setControlPlaneAuthenticationHandler(request -> false);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        // when
        channel.writeInbound(webSocketUpgradeRequest());

        // then - 401 and the channel is NOT upgraded to a web socket
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(401));
    }

    @Test
    public void shouldAllowWebSocketUpgradeWhenControlPlaneAuthEnabledAndAuthenticated() {
        // given - control-plane auth configured and the upgrade is authenticated
        HttpState httpState = newAsyncHttpState();
        httpState.setControlPlaneAuthenticationHandler(request -> true);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        // when
        channel.writeInbound(webSocketUpgradeRequest());

        // then - the gate allowed the upgrade so NO 401/403 rejection is written. The real
        // WebSocket handshake needs an HTTP codec on the pipeline (present in production, absent
        // in this bare EmbeddedChannel), so it writes no capturable 101 here — the security-
        // relevant assertion is the absence of a rejection (contrast the reject test below).
        assertNotRejected(channel.readOutbound());
    }

    @Test
    public void shouldAllowWebSocketUpgradeWhenNoControlPlaneAuthConfigured() {
        // given - default config: NO control-plane auth handler set, so the dashboard stays open
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        // when
        channel.writeInbound(webSocketUpgradeRequest());

        // then - non-breaking default: no credentials required, no rejection written
        assertNotRejected(channel.readOutbound());
    }

    private static void assertNotRejected(FullHttpResponse response) {
        // The gate allowed the upgrade: either the handshake proceeded (101 Switching Protocols)
        // or nothing capturable was written in this bare channel. Either way it must NOT be a
        // 401/403 auth challenge.
        if (response != null) {
            assertThat(response.status().code(), not(anyOf(is(401), is(403))));
        }
    }

    @Test
    public void shouldHoldHandshakerPerChannelForConcurrentDashboardUpgrades() {
        // given - ONE @Sharable handler instance serving two dashboard connections, exactly as
        // production wires it (a single handler added to every dashboard channel's pipeline)
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channelOne = track(new EmbeddedChannel(handler));
        EmbeddedChannel channelTwo = track(new EmbeddedChannel(handler));

        // when - both connections upgrade through the SAME handler instance, the second after the first
        channelOne.writeInbound(webSocketUpgradeRequest());
        channelTwo.writeInbound(webSocketUpgradeRequest());

        // then - each channel kept its OWN handshaker; the second upgrade did not overwrite the
        // first's. A shared mutable instance field would have left both channels pointing at the
        // second channel's handshaker, so a close of the first could run with the wrong one.
        WebSocketServerHandshaker handshakerOne = DashboardWebSocketHandler.handshakerForChannel(channelOne);
        WebSocketServerHandshaker handshakerTwo = DashboardWebSocketHandler.handshakerForChannel(channelTwo);
        assertThat(handshakerOne, is(notNullValue()));
        assertThat(handshakerTwo, is(notNullValue()));
        assertThat(handshakerOne, is(not(sameInstance(handshakerTwo))));
    }

    @Test
    public void shouldRemainSharableBecauseHttp2MultiplexAddsOneInstanceToEveryStreamPipeline() {
        // @Sharable is load-bearing, not an optimisation: Http2MultiplexChildInitializer adds ONE handler
        // instance to every HTTP/2 stream pipeline (and shouldHoldHandshakerPerChannelForConcurrentDashboardUpgrades
        // adds one instance to two channels), so Netty's checkMultiplicity throws without it. This pins the
        // decision NOT to remove the annotation.
        assertThat(
            DashboardWebSocketHandler.class.isAnnotationPresent(ChannelHandler.Sharable.class),
            is(true));
    }

    @Test
    public void shouldGiveEachHandlerItsOwnThrottleExecutorSoClosingOneDoesNotDisableAnother() throws Exception {
        // Each connection owns its scheduler/throttleExecutorService, so handlerRemoved closing one
        // connection must never disable the throttle/coalescer of another. Sharing those executors (e.g.
        // making them static to "optimise" the @Sharable instance) would let the first close break the
        // throttle for every other open connection - the invariant this test pins.
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler first = track(new DashboardWebSocketHandler(httpState, false, false)).registerListeners();
        DashboardWebSocketHandler second = track(new DashboardWebSocketHandler(httpState, false, false)).registerListeners();

        ScheduledExecutorService firstThrottle = throttleExecutorOf(first);
        ScheduledExecutorService secondThrottle = throttleExecutorOf(second);
        assertThat("each handler owns a distinct throttle executor",
            firstThrottle, is(not(sameInstance(secondThrottle))));

        // mirror production handlerRemoved for the FIRST connection only
        stopHandlerExecutors(first);

        // the second connection's throttle executor must still be open AND able to run work
        assertThat("second handler's throttle executor still open after first is closed",
            secondThrottle.isShutdown(), is(false));
        CountDownLatch ran = new CountDownLatch(1);
        secondThrottle.schedule(ran::countDown, 0, MILLISECONDS);
        assertThat("second handler's throttle executor still runs work after first is closed",
            ran.await(2, SECONDS), is(true));
    }

    private static ScheduledExecutorService throttleExecutorOf(DashboardWebSocketHandler handler) {
        try {
            java.lang.reflect.Field field = DashboardWebSocketHandler.class.getDeclaredField("throttleExecutorService");
            field.setAccessible(true);
            return (ScheduledExecutorService) field.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read DashboardWebSocketHandler.throttleExecutorService", e);
        }
    }

    private static DefaultFullHttpRequest webSocketUpgradeRequest(String uri) {
        DefaultFullHttpRequest upgradeRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, uri
        );
        upgradeRequest.headers().set(HttpHeaderNames.HOST, "localhost");
        upgradeRequest.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        upgradeRequest.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        upgradeRequest.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        upgradeRequest.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        return upgradeRequest;
    }

    // -----------------------------------------------------------------------------------------------
    // Option 7: a dashboard client may request a per-connection LOG-ROW limit within a server-enforced
    // range, via a query parameter on the upgrade URI. Expectations are NOT client-tunable.
    // -----------------------------------------------------------------------------------------------

    @Test
    public void resolveLogItemLimitFailsTowardDefaultAndClampsToMaximum() {
        int def = DashboardWebSocketHandler.defaultLogItemLimitForTesting();
        int max = DashboardWebSocketHandler.maxLogItemLimitForTesting();

        // absent -> DEFAULT (a client that asks for nothing costs exactly what it costs today)
        assertThat("absent", DashboardWebSocketHandler.resolveLogItemLimit(null), is(def));
        // blank / whitespace -> DEFAULT (never read as "give me everything")
        assertThat("empty", DashboardWebSocketHandler.resolveLogItemLimit(""), is(def));
        assertThat("whitespace", DashboardWebSocketHandler.resolveLogItemLimit("   "), is(def));
        // valid, in range and ABOVE the default -> honoured verbatim
        assertThat("in-range above default", DashboardWebSocketHandler.resolveLogItemLimit("150"), is(150));
        // valid but BELOW the default -> honoured (the client asked for less); default never rises
        assertThat("in-range below default", DashboardWebSocketHandler.resolveLogItemLimit("50"), is(50));
        assertThat("surrounding whitespace tolerated", DashboardWebSocketHandler.resolveLogItemLimit(" 200 "), is(200));
        // zero -> DEFAULT, negative -> DEFAULT
        assertThat("zero", DashboardWebSocketHandler.resolveLogItemLimit("0"), is(def));
        assertThat("negative", DashboardWebSocketHandler.resolveLogItemLimit("-5"), is(def));
        // non-numeric (garbage, and a non-integer) -> DEFAULT, NOT the maximum
        assertThat("non-numeric", DashboardWebSocketHandler.resolveLogItemLimit("abc"), is(def));
        assertThat("non-integer", DashboardWebSocketHandler.resolveLogItemLimit("12.5"), is(def));
        // exactly the maximum -> the maximum; above -> clamped DOWN to the maximum
        assertThat("at max", DashboardWebSocketHandler.resolveLogItemLimit(String.valueOf(max)), is(max));
        assertThat("above max", DashboardWebSocketHandler.resolveLogItemLimit(String.valueOf(max + 1)), is(max));
        // a huge value (and overflow-adjacent) -> clamped to the maximum, never allowed through
        assertThat("huge", DashboardWebSocketHandler.resolveLogItemLimit("100000"), is(max));
        assertThat("Integer.MAX_VALUE", DashboardWebSocketHandler.resolveLogItemLimit(String.valueOf(Integer.MAX_VALUE)), is(max));
    }

    @Test
    public void bareUpgradeUriStillUpgradesAndUsesDefaultLogLimit() {
        // Regression guard for the equality->path change: a bare URI (no query string), exactly as the
        // shipped UI sends, must still upgrade and resolve to the DEFAULT limit.
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        channel.writeInbound(webSocketUpgradeRequest("/_mockserver_ui_websocket"));

        assertNotRejected(channel.readOutbound());
        assertThat("bare upgrade stores the DEFAULT log limit",
            DashboardWebSocketHandler.logItemLimitForChannel(channel),
            is(DashboardWebSocketHandler.defaultLogItemLimitForTesting()));
    }

    @Test
    public void upgradeUriWithLogLimitQueryUpgradesAndStoresRequestedLimit() {
        // A query string must NOT break the upgrade (the old exact-equality match would have rejected
        // it), and the in-range requested limit must be pinned to THIS channel.
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        channel.writeInbound(webSocketUpgradeRequest("/_mockserver_ui_websocket?logLimit=250"));

        assertNotRejected(channel.readOutbound());
        assertThat("in-range requested limit is stored on the channel",
            DashboardWebSocketHandler.logItemLimitForChannel(channel), is(250));
    }

    @Test
    public void upgradeUriWithExcessiveLogLimitQueryStoresClampedMaximum() {
        // An attacker-chosen huge value on the (unauthenticated-by-default) upgrade must be clamped at
        // the single choke point to the server maximum before it reaches the channel.
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        channel.writeInbound(webSocketUpgradeRequest("/_mockserver_ui_websocket?logLimit=100000"));

        assertNotRejected(channel.readOutbound());
        assertThat("excessive requested limit is clamped to the server maximum",
            DashboardWebSocketHandler.logItemLimitForChannel(channel),
            is(DashboardWebSocketHandler.maxLogItemLimitForTesting()));
    }

    @Test
    public void foreignPathIsNotTreatedAsDashboardUpgrade() {
        // The path comparison must NOT widen what counts as the dashboard upgrade path: a different
        // path is passed through un-upgraded (no limit pinned), even though it shares a prefix.
        HttpState httpState = newAsyncHttpState();
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = track(new EmbeddedChannel(handler));

        DefaultFullHttpRequest foreign = webSocketUpgradeRequest("/_mockserver_ui_websocket_evil");
        channel.writeInbound(foreign);

        assertThat("foreign path is not upgraded, so no log limit is pinned",
            DashboardWebSocketHandler.logItemLimitForChannel(channel), is(nullValue()));
        assertThat("foreign request is passed on down the pipeline", channel.readInbound(), is(sameInstance((Object) foreign)));
    }

    @Test
    public void populateLogSectionsHonoursClientLogLimit() {
        // The split log limit actually caps the log sections at the client-supplied value - and can go
        // ABOVE the old fixed 100. Drive the extracted consumer directly for an exact assertion.
        List<DashboardLogEntryDTO> reverse = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reverse.add(logDto(FORWARDED_REQUEST, "/proxied-" + i));
        }
        for (int i = 0; i < 20; i++) {
            reverse.add(logDto(RECEIVED_REQUEST, "/recorded-" + i));
        }

        List<Object> logMessages = new LinkedList<>();
        List<Map<String, Object>> recordedRequests = new LinkedList<>();
        List<Map<String, Object>> proxiedRequests = new LinkedList<>();
        DashboardWebSocketHandler.populateLogSections(
            reverse.stream(), true, 5,
            logMessages, recordedRequests, proxiedRequests,
            new DescriptionProcessor(), new DescriptionProcessor(), new DescriptionProcessor());

        assertThat("recorded rows capped at the client limit", recordedRequests.size(), is(5));
        assertThat("proxied rows capped at the client limit", proxiedRequests.size(), is(5));
        assertThat("log messages capped at the client limit", logMessages.size(), is(5));

        // A limit above the old fixed 100 admits more than 100 rows (proving the cap is the parameter,
        // not a residual constant).
        List<DashboardLogEntryDTO> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(logDto(RECEIVED_REQUEST, "/recorded-" + i));
        }
        List<Map<String, Object>> recordedAbove100 = new LinkedList<>();
        DashboardWebSocketHandler.populateLogSections(
            many.stream(), true, 130,
            new LinkedList<>(), recordedAbove100, new LinkedList<>(),
            new DescriptionProcessor(), new DescriptionProcessor(), new DescriptionProcessor());
        assertThat("a limit above 100 admits more than 100 rows", recordedAbove100.size(), is(130));
    }

    // =============================================================================================
    // Unit 1: short-circuit the reverse UI log walk once all three output categories (logMessages,
    // recordedRequests, proxiedRequests) have each reached UI_UPDATE_ITEM_LIMIT. The consumer used to
    // walk the ENTIRE event log every update and merely stop ADDING after 100 in each category, so
    // the per-update cost was O(entire log). The short-circuit stops the walk once nothing more can be
    // added, turning it into O(depth actually consumed) WITHOUT changing the emitted frame.
    //
    // Two properties are proved separately:
    //   (1) OUTPUT INVARIANCE (byte-identical): populateLogSections(shortCircuit=true) produces
    //       byte-identical sections to populateLogSections(shortCircuit=false) over the SAME DTOs.
    //   (2) WORK REDUCTION: on a large unfiltered log the number of DashboardLogEntryDTOs actually
    //       constructed collapses from ~= log size to ~= the depth needed to fill the categories.
    // =============================================================================================

    private static final ObjectWriter SECTIONS_WRITER = ObjectMapperFactory.createObjectMapper(
        new DashboardLogEntryDTOSerializer(),
        new DashboardLogEntryDTOGroupSerializer(),
        new DescriptionSerializer(),
        new ThrowableSerializer()
    ).writerWithDefaultPrettyPrinter();

    private static DashboardLogEntryDTO logDto(LogEntry.LogMessageType type, String path) {
        return new DashboardLogEntryDTO(
            new LogEntry().setType(type).setHttpRequest(request(path)).setHttpResponse(response("ok")).setMessageFormat("m {}").setArguments(path),
            configuration());
    }

    // Drive the extracted consumer over the given reverse-ordered DTOs and serialise the three
    // resulting sections. Fresh DescriptionProcessors per call, exactly as production does per update.
    private static String renderSections(List<DashboardLogEntryDTO> reverseOrdered, boolean shortCircuit) throws Exception {
        List<Object> logMessages = new LinkedList<>();
        List<Map<String, Object>> recordedRequests = new LinkedList<>();
        List<Map<String, Object>> proxiedRequests = new LinkedList<>();
        DashboardWebSocketHandler.populateLogSections(
            reverseOrdered.stream(), shortCircuit, 100,
            logMessages, recordedRequests, proxiedRequests,
            new DescriptionProcessor(), new DescriptionProcessor(), new DescriptionProcessor());
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("logMessages", logMessages);
        sections.put("recordedRequests", recordedRequests);
        sections.put("proxiedRequests", proxiedRequests);
        return SECTIONS_WRITER.writeValueAsString(sections);
    }

    @Test
    public void shortCircuitProducesByteIdenticalSectionsToFullWalk() throws Exception {
        // Reverse (newest-first) order chosen so recordedRequests is the LAST category to fill: the
        // 100 FORWARDED entries fill proxiedRequests and logMessages first, then the 100 RECEIVED
        // entries fill recordedRequests, then 3000 deeper RECEIVED entries that the caps must drop.
        // A short-circuit that stopped as soon as ANY category was full would drop the RECEIVED block
        // and diverge from the full walk — so this data makes that bug observable.
        List<DashboardLogEntryDTO> reverse = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            reverse.add(logDto(FORWARDED_REQUEST, "/proxied-" + i));
        }
        for (int i = 0; i < 100; i++) {
            reverse.add(logDto(RECEIVED_REQUEST, "/recorded-" + i));
        }
        for (int i = 0; i < 3000; i++) {
            reverse.add(logDto(RECEIVED_REQUEST, "/filler-" + i));
        }

        String shortCircuited = renderSections(reverse, true);
        String fullWalk = renderSections(reverse, false);

        // (1) byte-identical
        assertThat("short-circuit output must be byte-identical to the full walk", shortCircuited, is(fullWalk));
        // (2) and it is the genuinely full frame, not two empty frames coincidentally equal
        assertThat("recordedRequests filled to the cap", countOccurrences(fullWalk, "_request\""), is(100));
        assertThat("proxiedRequests filled to the cap", countOccurrences(fullWalk, "_proxied\""), is(100));
        assertThat("logMessages filled to the cap", countOccurrences(fullWalk, "_log\""), is(100));
    }

    // ---------------------------------------------------------------------------------------------
    // End-to-end (real MockServerEventLog + real sendUpdate) tests. logDtoConstructionCountForTesting()
    // counts DashboardLogEntryDTO constructions on THIS handler's stream, so it measures the walk
    // depth directly. A single controlled scan is taken (throttle permit ensured free first, so the
    // first send is not retried into extra scans).
    // ---------------------------------------------------------------------------------------------

    private static LogEntry received(String path) {
        return new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request(path)).setMessageFormat("received {}").setArguments(path);
    }

    private static LogEntry forwarded(String path) {
        return new LogEntry().setType(FORWARDED_REQUEST).setHttpRequest(request(path)).setHttpResponse(response("ok")).setMessageFormat("forwarded {}").setArguments(path);
    }

    private DashboardWebSocketHandler newSeededHandler(List<LogEntry> entries) {
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketHandlerTest.class);
        Configuration configuration = configuration().maxLogEntries(50000);
        Scheduler scheduler = track(new Scheduler(configuration, mockServerLogger, true));
        HttpState httpState = track(new HttpState(configuration, mockServerLogger, scheduler));
        MockServerEventLog eventLog = httpState.getMockServerLog();
        for (LogEntry entry : entries) {
            eventLog.add(entry);
        }
        return track(new DashboardWebSocketHandler(httpState, false, true)).registerListeners();
    }

    private static final class ScanResult {
        private final String frame;
        private final long dtosConstructed;

        private ScanResult(String frame, long dtosConstructed) {
            this.frame = frame;
            this.dtosConstructed = dtosConstructed;
        }
    }

    // One controlled scan: let the post-registration burst drain, reset the counter, then re-drive the
    // update until a frame lands, and finally let the async counter settle. Returns the frame text and
    // the DTOs constructed.
    //
    // Re-drive rather than send once. sendUpdate is throttled to a SINGLE write per second across ALL of
    // a handler's dashboards (one shared Semaphore(1) permit refilled once per second), and its internal
    // retry is bounded to a handful of attempts (retryCount starts at 2) before it silently DROPS the
    // update. So a single sendUpdate has only about a second of retry life: if a sibling dashboard holds
    // the permit through that window -- which happens whenever more than one connection is registered
    // (see multipleDashboardsWithDifferentFiltersEachSeeTheirOwnView, where the initial post-registration
    // update fans out to both connections) and the async pipeline is stretched under the parallel
    // surefire provider -- all of that one send's attempts miss the permit, retryCount reaches -1, and NO
    // frame is ever delivered, so the passive wait below times out. That is a latent race in this HELPER,
    // not in production: production dashboards are driven by a continuous stream of log/matcher updates,
    // and the two other end-to-end helpers here (awaitFrame, and the eviction test) ALREADY re-drive for
    // exactly this reason. Re-driving is deterministic, not merely less flaky: the permit is refilled
    // every second and the sibling's post-registration burst is one-shot and bounded, so once it drains
    // there is no other consumer and a caller that keeps issuing fresh updates every 400ms wins the very
    // next permit. It does not weaken any assertion -- state is static across these tests, so every frame
    // this produces is byte-identical to the one a single successful send would have produced.
    private ScanResult singleScan(DashboardWebSocketHandler handler, MockChannelHandlerContext ctx, RequestDefinition filter) throws InterruptedException {
        Thread.sleep(1200); // let the initial post-registration update drain before measuring
        ctx.textWebSocketFrame = null;
        handler.resetLogDtoConstructionCountForTesting();
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && ctx.textWebSocketFrame == null) {
            handler.sendUpdate(ctx, filter);
            Thread.sleep(400);
        }
        // allow the off-thread scan's counter to settle
        long previous = -1;
        long settleDeadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < settleDeadline) {
            long current = handler.logDtoConstructionCountForTesting();
            if (current == previous) {
                break;
            }
            previous = current;
            Thread.sleep(150);
        }
        return new ScanResult(ctx.textWebSocketFrame == null ? null : ctx.textWebSocketFrame.text(),
            handler.logDtoConstructionCountForTesting());
    }

    /**
     * The work reduction, measured DETERMINISTICALLY by counting how many entries the short-circuit
     * actually pulls from the stream.
     * <p>
     * An earlier version of this test drove a real end-to-end scan and read an instance-scoped DTO
     * counter. It was FLAKY and review caught it failing 1 run in 4 with 2,211 against a `< 500`
     * bound. The counter was not wrong about the code — 2,211 is about 11 x 201, i.e. each
     * individual scan short-circuited correctly and the counter had summed roughly eleven of them.
     * Two things fire extra scans inside the measurement window: the retry path re-schedules
     * sendUpdate whenever the once-per-second permit is contended, and each scan's own querySnapshot
     * publishes to the disruptor, which this handler listens to. Under the parallel surefire provider
     * that contention is exactly what CI produces.
     * <p>
     * So the instrument moved to where it can be exact: drive populateLogSections directly, as the
     * byte-identity test does, and count pulls with a peek. No scheduler, no throttle, no listener
     * feedback — the number is the walk depth and nothing else.
     */
    @Test
    public void shortCircuitPullsOnlyTheDepthItNeeds() throws Exception {
        // Newest-first: 100 FORWARDED fill proxied + logMessages, then 100 RECEIVED fill recorded,
        // then 4,000 deeper entries the caps must never touch.
        List<DashboardLogEntryDTO> reverse = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            reverse.add(logDto(FORWARDED_REQUEST, "/proxied-" + i));
        }
        for (int i = 0; i < 100; i++) {
            reverse.add(logDto(RECEIVED_REQUEST, "/recorded-" + i));
        }
        for (int i = 0; i < 4000; i++) {
            reverse.add(logDto(RECEIVED_REQUEST, "/filler-" + i));
        }

        long shortCircuited = countPulls(reverse, true);
        long fullWalk = countPulls(reverse, false);

        // 200 to fill all three categories, plus the one element whose takeWhile test fails and ends
        // the stream. Exact, not a loose bound - a loose bound is what hid the flake.
        assertThat("short-circuit pulls only the depth it needs", shortCircuited, is(201L));
        assertThat("the un-short-circuited walk pulls everything", fullWalk, is(4200L));
    }

    /** Counts how many elements populateLogSections actually pulls from the stream. */
    private static long countPulls(List<DashboardLogEntryDTO> reverseOrdered, boolean shortCircuit) throws Exception {
        AtomicLong pulled = new AtomicLong();
        DashboardWebSocketHandler.populateLogSections(
            reverseOrdered.stream().peek(ignored -> pulled.incrementAndGet()), shortCircuit, 100,
            new LinkedList<>(), new LinkedList<>(), new LinkedList<>(),
            new DescriptionProcessor(), new DescriptionProcessor(), new DescriptionProcessor());
        return pulled.get();
    }

    @Test
    public void filteredDashboardStillFindsSparseMatchesDeepInTheLog() throws Exception {
        // 5 matching /needle RECEIVED at the BOTTOM (oldest / deepest in reverse), buried under 3000
        // non-matching /other RECEIVED. The categories never fill (only 5 match), so the short-circuit
        // must NOT stop early — the deep matches must still be found.
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(received("/needle-" + i));
        }
        for (int i = 0; i < 3000; i++) {
            entries.add(received("/other-" + i));
        }
        DashboardWebSocketHandler handler = newSeededHandler(entries);
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());
        HttpRequest filter = request("/needle-.*");
        handler.getClientRegistry().put(ctx, filter);

        ScanResult result = singleScan(handler, ctx, filter);

        assertThat("a frame was produced", result.frame, is(not(nullValue())));
        assertThat("all 5 sparse deep matches were found", countOccurrences(result.frame, "_request\""), is(5));
        assertThat("only matching traffic is shown", result.frame, containsString("/needle-"));
        assertThat("non-matching traffic is excluded", result.frame, not(containsString("/other-")));
    }

    @Test
    public void fewerEntriesThanCapAreAllRendered() throws Exception {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(received("/recorded-" + i));
        }
        for (int i = 0; i < 10; i++) {
            entries.add(forwarded("/proxied-" + i));
        }
        DashboardWebSocketHandler handler = newSeededHandler(entries);
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());
        handler.getClientRegistry().put(ctx, request());

        ScanResult result = singleScan(handler, ctx, request());

        assertThat("a frame was produced", result.frame, is(not(nullValue())));
        assertThat("all recorded requests rendered", countOccurrences(result.frame, "_request\""), is(10));
        assertThat("all proxied requests rendered", countOccurrences(result.frame, "_proxied\""), is(10));
    }

    @Test
    public void emptyLogProducesEmptySections() throws Exception {
        DashboardWebSocketHandler handler = newSeededHandler(Collections.emptyList());
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());
        handler.getClientRegistry().put(ctx, request());

        ScanResult result = singleScan(handler, ctx, request());

        assertThat("a frame was produced", result.frame, is(not(nullValue())));
        assertThat("no recorded requests", countOccurrences(result.frame, "_request\""), is(0));
        assertThat("no proxied requests", countOccurrences(result.frame, "_proxied\""), is(0));
        assertThat("no DTOs constructed for an empty log", result.dtosConstructed, is(0L));
    }

    @Test
    public void multipleDashboardsWithDifferentFiltersEachSeeTheirOwnView() throws Exception {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            entries.add(received("/needle-" + i));
        }
        for (int i = 0; i < 3; i++) {
            entries.add(received("/other-" + i));
        }
        DashboardWebSocketHandler handler = newSeededHandler(entries);

        MockChannelHandlerContext unfiltered = track(new MockChannelHandlerContext());
        handler.getClientRegistry().put(unfiltered, request());
        MockChannelHandlerContext filtered = track(new MockChannelHandlerContext());
        HttpRequest filter = request("/needle-.*");
        handler.getClientRegistry().put(filtered, filter);

        ScanResult unfilteredResult = singleScan(handler, unfiltered, request());
        ScanResult filteredResult = singleScan(handler, filtered, filter);

        assertThat(unfilteredResult.frame, is(not(nullValue())));
        assertThat(filteredResult.frame, is(not(nullValue())));
        // The unfiltered dashboard sees all six requests.
        assertThat("unfiltered dashboard sees all traffic", countOccurrences(unfilteredResult.frame, "_request\""), is(6));
        // The filtered dashboard sees only its three matches.
        assertThat("filtered dashboard sees only its matches", countOccurrences(filteredResult.frame, "_request\""), is(3));
        assertThat(filteredResult.frame, not(containsString("/other-")));
    }

    // =============================================================================================
    // Connection-limit eviction observability. getClientRegistry() is a CircularHashMap bounded to
    // DASHBOARD_CONNECTION_LIMIT (100). The (limit+1)th connection evicts the eldest. That eviction
    // USED to be silent - the evicted browser kept an open socket that simply stopped updating forever.
    // registerClient() now makes it observable: the evicted connection is CLOSED (so the UI's existing
    // reconnect handling engages) and logged, while the surviving connections keep receiving updates.
    // =============================================================================================

    @Test
    public void evictingTheOldestDashboardConnectionClosesItAndKeepsServingTheSurvivors() throws Exception {
        // given - a handler with a single log entry so a survivor's update carries observable content
        DashboardWebSocketHandler handler = newSeededHandler(Collections.singletonList(received("/only")));

        // when - fill the registry to exactly the connection limit (100); none of these evicts
        List<MockChannelHandlerContext> connections = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());
            connections.add(ctx);
            assertThat("no eviction while under the limit", handler.registerClient(ctx), is(nullValue()));
        }
        MockChannelHandlerContext oldest = connections.get(0);

        // ... then the 101st connection must evict the OLDEST
        MockChannelHandlerContext overflow = track(new MockChannelHandlerContext());
        ChannelOutboundInvoker evicted = handler.registerClient(overflow);
        oldest.runPendingTasks(); // flush the close task on the embedded event loop

        // then - the evicted connection is OBSERVABLE, not silently frozen
        assertThat("the oldest connection is the one evicted", evicted, is(sameInstance((ChannelOutboundInvoker) oldest)));
        assertThat("the evicted connection was CLOSED so its browser will reconnect", oldest.isOpen(), is(false));
        assertThat("the evicted connection left the registry", handler.getClientRegistry().containsKey(oldest), is(false));

        // ... and the bound is preserved: still exactly 100 members, with the newcomer among them
        assertThat("registry still bounded to the connection limit", handler.getClientRegistry().size(), is(100));
        assertThat("the new connection is registered", handler.getClientRegistry().containsKey(overflow), is(true));

        // ... and the surviving connections still receive updates
        MockChannelHandlerContext survivor = connections.get(50);
        assertThat("a survivor is still registered", handler.getClientRegistry().containsKey(survivor), is(true));
        assertThat("a survivor is still open", survivor.isOpen(), is(true));
        // Re-send until a frame lands (the once-per-second global throttle is shared across all 100
        // survivors here, so a single send may not win a permit) - exactly the retry pattern awaitFrame
        // uses. The point is that the survivor is STILL SERVED at all, unlike the evicted connection.
        survivor.textWebSocketFrame = null;
        String survivorFrame = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            handler.sendUpdate(survivor, request());
            Thread.sleep(400);
            if (survivor.textWebSocketFrame != null) {
                survivorFrame = survivor.textWebSocketFrame.text();
                break;
            }
        }
        assertThat("a surviving dashboard still receives update frames", survivorFrame, is(not(nullValue())));
        assertThat("the survivor's update carries live content", survivorFrame, containsString("/only"));
    }


    // =============================================================================================
    // Client-pull coalescing. The dashboard WebSocket is UNAUTHENTICATED BY DEFAULT and the client
    // controls both the inbound-frame rate and the deserialised filter, so before this bound each frame
    // drove a fresh logItemLimit-deep walk with no rate limit at all. schedulePullUpdate coalesces the
    // pull path per channel (leading + trailing), so a burst collapses to at most one leading + one
    // trailing walk per window while the LAST filter is always the one finally served.
    //
    // pullUpdateDispatchCountForTesting() counts DISPATCHES (walks triggered by the coalescer), not
    // inbound frames, and is immune to the sendMessage retry path - so the count is EXACT, not a loose
    // bound. Frames for one channel are serialised on its event loop (as in production), so a tight
    // synchronous burst lands entirely within one window and the trailing dispatch is deterministic.
    // =============================================================================================

    @Test
    public void pullPathCoalescesABurstOfInboundFramesIntoBoundedWalks() throws Exception {
        // given - a live pull connection on an otherwise-idle handler
        DashboardWebSocketHandler handler = newSeededHandler(Collections.singletonList(received("/only")));
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());

        // when - a tight burst of 20 inbound pull requests, all well within one PULL_COALESCE window
        for (int i = 0; i < 20; i++) {
            handler.schedulePullUpdate(ctx, request("/burst-" + i));
        }
        // let the trailing window elapse and its flush run (window is 250ms; wait generously)
        Thread.sleep(1500);

        // then - NOT one walk per frame: exactly one leading walk (first frame, served immediately) plus
        // one trailing walk (the collapsed burst), regardless of the 20 frames delivered.
        assertThat("a 20-frame burst dispatches only the leading walk plus one trailing walk",
            handler.pullUpdateDispatchCountForTesting(), is(2L));
    }

    @Test
    public void pullPathReflectsTheLastFilterAfterABurst() throws Exception {
        // given - a live pull connection
        DashboardWebSocketHandler handler = newSeededHandler(Collections.singletonList(received("/only")));
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());

        // when - a burst whose FINAL filter differs from every superseded one in the middle
        HttpRequest last = request("/the-last-filter");
        handler.schedulePullUpdate(ctx, request("/first"));
        for (int i = 0; i < 10; i++) {
            handler.schedulePullUpdate(ctx, request("/superseded-" + i));
        }
        handler.schedulePullUpdate(ctx, last);
        Thread.sleep(1500);

        // then - the burst still collapsed (leading + trailing only) ...
        assertThat("burst collapsed to leading + trailing, never one walk per frame",
            handler.pullUpdateDispatchCountForTesting(), is(2L));
        // ... and the LAST filter the client sent is the one finally served: the trailing walk carries it,
        // so the UI can never be left showing results for a superseded filter (no lost update).
        assertThat("the last filter the client sent is the one finally served",
            handler.lastPullFilterDispatchedForTesting(), is(sameInstance((RequestDefinition) last)));
    }

    @Test
    public void shouldCarryLogEntryTimestampOnRecordedAndProxiedRequests() throws Exception {
        // Every recorded (RECEIVED_REQUEST) and proxied (FORWARDED_REQUEST) row must carry the log
        // entry's OWN timestamp - the dashboard shows it in place of an ordinal, which renumbered on
        // every push. Both sections are covered: the proxied one is a separate branch behind its own
        // "if (!value.isEmpty())" guard, so it is asserted explicitly, not assumed to follow from the
        // recorded one. Each entry is pinned to a fixed, distinct epoch well in the past (2020), so the
        // expected timestamp is deterministic AND is provably the entry's own time rather than the time
        // the frame was built. The expected string is captured BEFORE the entry is added to the log,
        // because the async event log publishes a copy and then CLEARS the source entry (epochTime=-1).
        LogEntry recordedEntry = received("/recorded-request").setEpochTime(1_600_000_000_123L);
        LogEntry proxiedEntry = forwarded("/proxied-request").setEpochTime(1_600_000_222_456L);
        String expectedRecordedTimestamp = recordedEntry.getTimestamp();
        String expectedProxiedTimestamp = proxiedEntry.getTimestamp();
        DashboardWebSocketHandler handler = newSeededHandler(Arrays.asList(recordedEntry, proxiedEntry));
        MockChannelHandlerContext ctx = track(new MockChannelHandlerContext());
        handler.getClientRegistry().put(ctx, request());

        ScanResult result = singleScan(handler, ctx, request());
        assertThat("a frame was produced", result.frame, is(not(nullValue())));

        JsonNode tree = ObjectMapperFactory.createObjectMapper().readTree(result.frame);
        assertTimestampOnEveryRow(tree.get("recordedRequests"), expectedRecordedTimestamp, "recordedRequests");
        assertTimestampOnEveryRow(tree.get("proxiedRequests"), expectedProxiedTimestamp, "proxiedRequests");
    }

    // Assert the section is present and non-empty, and every row carries a non-null, non-blank
    // timestamp equal to the log entry's own timestamp.
    private static void assertTimestampOnEveryRow(JsonNode section, String expectedTimestamp, String name) {
        assertThat(name + " section is present", section, is(notNullValue()));
        assertThat(name + " section is non-empty", section.size(), is(not(0)));
        for (JsonNode entry : section) {
            JsonNode timestamp = entry.get("timestamp");
            assertThat(name + " row carries a timestamp", timestamp, is(notNullValue()));
            assertThat(name + " row timestamp is non-empty", timestamp.asText().isEmpty(), is(false));
            assertThat(name + " row timestamp is the log entry's own timestamp", timestamp.asText(), is(expectedTimestamp));
        }
    }

    public static class MockChannelHandlerContext extends EmbeddedChannel {

        // can't use future as called multiple times
        TextWebSocketFrame textWebSocketFrame;

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof TextWebSocketFrame) {
                textWebSocketFrame = (TextWebSocketFrame) msg;
            }
            return null;
        }
    }

}