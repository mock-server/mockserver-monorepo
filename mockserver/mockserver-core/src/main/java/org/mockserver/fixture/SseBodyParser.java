package org.mockserver.fixture;

import org.mockserver.model.Delay;
import org.mockserver.model.SseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Parses raw {@code text/event-stream} bytes into a list of {@link SseEvent} objects
 * suitable for constructing an {@link org.mockserver.model.HttpSseResponse}.
 * <p>
 * The parser follows the SSE specification (W3C Server-Sent Events):
 * <ul>
 *   <li>Events are separated by blank lines ({@code \n\n})</li>
 *   <li>Lines starting with {@code data:} set the data field</li>
 *   <li>Lines starting with {@code event:} set the event type</li>
 *   <li>Lines starting with {@code id:} set the last event ID</li>
 *   <li>Lines starting with {@code retry:} set the reconnection time</li>
 *   <li>Lines starting with {@code :} are comments; they are ignored unless the parser keeps comments, when the text
 *   after each {@code :}, leading space included, becomes the event's {@code comment} (so it replays byte for byte)
 *   and a block of only comments becomes an event with only a comment</li>
 *   <li>Multiple {@code data:} lines within one event are joined with {@code \n}</li>
 * </ul>
 * <p>
 * When per-chunk timestamps are available (captured by the streaming relay during recording),
 * the parser applies the actual inter-chunk delays to reproduce the original timing. Each block
 * (the text between blank lines) is taken to be one recorded chunk, so block {@code i} has delay
 * {@code i}; the delay of a block that becomes no event (a blank block, or a comment-only block
 * when comments are not kept) is added to the next event, so later events keep their recorded
 * timing. When per-chunk timestamps are absent (legacy recordings), a fixed inter-event delay is
 * applied to each event (except the first). The default fallback is 50 milliseconds.
 */
public class SseBodyParser {

    /** Default inter-event delay in milliseconds when per-chunk timestamps are not available. */
    public static final long DEFAULT_INTER_EVENT_DELAY_MS = 50;

    /** The longest delay a replayed event is given from recorded timing, so a bad recording cannot stall a stream. */
    public static final long MAX_REPLAYED_DELAY_MS = 60 * 60 * 1000L;

    private static final Pattern BLOCK_EDGE_LINE_BREAKS = Pattern.compile("^[\\r\\n]+|[\\r\\n]+$");

    private final long interEventDelayMs;
    private final boolean keepComments;

    /**
     * Create a parser with the default inter-event delay.
     */
    public SseBodyParser() {
        this(DEFAULT_INTER_EVENT_DELAY_MS);
    }

    /**
     * Create a parser with a custom inter-event delay.
     *
     * @param interEventDelayMs delay in milliseconds between events on replay
     */
    public SseBodyParser(long interEventDelayMs) {
        this(interEventDelayMs, false);
    }

    /**
     * @param interEventDelayMs delay in milliseconds between events on replay
     * @param keepComments      keep comment lines as each event's {@code comment}, and a comment-only block as
     *                          an event with only a comment, instead of dropping them
     */
    public SseBodyParser(long interEventDelayMs, boolean keepComments) {
        this.interEventDelayMs = Math.max(0, interEventDelayMs);
        this.keepComments = keepComments;
    }

    /**
     * Parse raw SSE body text into a list of {@link SseEvent} objects.
     *
     * @param sseText the raw SSE body text (e.g., captured from a forwarded streaming response)
     * @return the parsed events; empty list if the input is null or blank
     */
    public List<SseEvent> parse(String sseText) {
        return parse(sseText, null);
    }

    /**
     * Parse raw SSE body text into a list of {@link SseEvent} objects, optionally applying
     * per-chunk replay timing captured during recording.
     * <p>
     * When {@code perChunkDelaysMs} is non-null, block {@code i} takes the captured delay at index
     * {@code i}, and each event's delay is its own block's delay plus those of the blocks skipped since
     * the previous event. A block past the end of {@code perChunkDelaysMs}, or every block when it is
     * null, uses the fixed {@code interEventDelayMs} fallback for an event after the first and nothing
     * for a skipped block, preserving backward compatibility with recordings that predate per-chunk
     * timing capture.
     * <p>
     * Note: mapping chunk-level delays to blocks is approximate when HTTP chunk boundaries do not
     * align 1:1 with SSE blocks. The mapping is exact for streams that send one event or comment per
     * chunk, and best-effort otherwise.
     *
     * @param sseText           the raw SSE body text
     * @param perChunkDelaysMs  optional list of per-chunk delays in milliseconds (index 0 = the delay
     *                          before the first chunk, typically 0). May be null for fallback behaviour.
     * @return the parsed events; empty list if the input is null or blank
     */
    public List<SseEvent> parse(String sseText, List<Long> perChunkDelaysMs) {
        List<SseEvent> events = new ArrayList<>();
        if (sseText == null || sseText.isEmpty()) {
            return events;
        }

        // Split on blank lines (event boundaries in SSE). Handle both \n\n and \r\n\r\n.
        String[] rawBlocks = sseText.split("\\r?\\n\\r?\\n");
        long skippedDelayMs = 0;

        for (int blockIndex = 0; blockIndex < rawBlocks.length; blockIndex++) {
            String block = rawBlocks[blockIndex];
            // only line breaks are cut from the block's edges, so a comment keeps its spaces
            SseEvent event = block.trim().isEmpty() ? null : parseBlock(BLOCK_EDGE_LINE_BREAKS.matcher(block).replaceAll(""));
            boolean recorded = perChunkDelaysMs != null && blockIndex < perChunkDelaysMs.size();
            long blockDelayMs = recorded ? clamp(perChunkDelaysMs.get(blockIndex)) : 0;
            if (event == null) {
                skippedDelayMs = clamp(skippedDelayMs + blockDelayMs);
                continue;
            }
            if (!recorded && !events.isEmpty()) {
                blockDelayMs = interEventDelayMs;
            }
            long delayMs = clamp(skippedDelayMs + blockDelayMs);
            if (delayMs > 0) {
                event.withDelay(new Delay(TimeUnit.MILLISECONDS, delayMs));
            }
            events.add(event);
            skippedDelayMs = 0;
        }

        return events;
    }

    private static long clamp(Long delayMs) {
        return delayMs == null ? 0 : Math.max(0, Math.min(MAX_REPLAYED_DELAY_MS, delayMs));
    }

    private SseEvent parseBlock(String block) {
        String eventType = null;
        String id = null;
        Integer retry = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        StringBuilder comment = null;

        String[] lines = block.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith(":")) {
                if (keepComments) {
                    String text = line.substring(1);
                    comment = comment == null ? new StringBuilder(text) : comment.append('\n').append(text);
                }
                continue;
            }

            int colonIndex = line.indexOf(':');
            String field;
            String value;
            if (colonIndex >= 0) {
                field = line.substring(0, colonIndex);
                value = line.substring(colonIndex + 1);
                // SSE spec: if value starts with a space, strip it
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
            } else {
                field = line;
                value = "";
            }

            switch (field) {
                case "data":
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                    break;
                case "event":
                    eventType = value;
                    break;
                case "id":
                    id = value;
                    break;
                case "retry":
                    try {
                        retry = Integer.parseInt(value.trim());
                    } catch (NumberFormatException ignored) {
                        // SSE spec says non-numeric retry values are ignored
                    }
                    break;
                default:
                    // Unknown field; ignore per SSE spec
                    break;
            }
        }

        // An event block with no data and no event type is not a valid SSE event
        if (!hasData && eventType == null && id == null && retry == null && comment == null) {
            return null;
        }

        SseEvent event = sseEvent();
        if (comment != null) {
            event.withComment(comment.toString());
        }
        if (hasData) {
            event.withData(data.toString());
        }
        if (eventType != null) {
            event.withEvent(eventType);
        }
        if (id != null) {
            event.withId(id);
        }
        if (retry != null) {
            event.withRetry(retry);
        }

        return event;
    }
}
