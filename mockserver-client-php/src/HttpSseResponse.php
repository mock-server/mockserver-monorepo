<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a Server-Sent Events (SSE) response action.
 *
 * Produces the {@code httpSseResponse} action JSON. Wire keys:
 * statusCode, headers, events, closeConnection, closeDelay, keepStreamOpen, delay, primary, templateType.
 *
 * @example
 *   HttpSseResponse::response()
 *       ->statusCode(200)
 *       ->header('Content-Type', 'text/event-stream')
 *       ->event(SseEvent::event()->withData('hello'))
 *       ->event(SseEvent::event()->withEvent('update')->withData('{"x":1}'));
 */
class HttpSseResponse implements \JsonSerializable
{
    private ?int $statusCode = null;
    /** @var array<string, list<string>> */
    private array $headers = [];
    /** @var list<SseEvent> */
    private array $events = [];
    private ?bool $closeConnection = null;
    private ?Delay $closeDelay = null;
    private ?bool $keepStreamOpen = null;
    private ?Delay $delay = null;
    private ?bool $primary = null;
    private ?string $templateType = null;

    public static function response(): self
    {
        return new self();
    }

    public function statusCode(int $statusCode): self
    {
        $this->statusCode = $statusCode;
        return $this;
    }

    /**
     * Add a response header (multi-value supported).
     */
    public function header(string $name, string ...$values): self
    {
        if (!isset($this->headers[$name])) {
            $this->headers[$name] = [];
        }
        foreach ($values as $value) {
            $this->headers[$name][] = $value;
        }
        return $this;
    }

    /**
     * Append a single SSE event.
     */
    public function event(SseEvent $event): self
    {
        $this->events[] = $event;
        return $this;
    }

    /**
     * Replace the full event list.
     *
     * @param list<SseEvent> $events
     */
    public function events(array $events): self
    {
        $this->events = array_values($events);
        return $this;
    }

    public function closeConnection(bool $closeConnection): self
    {
        $this->closeConnection = $closeConnection;
        return $this;
    }

    /**
     * Wait this long after the last event before ending the stream.
     */
    public function closeDelay(Delay $closeDelay): self
    {
        $this->closeDelay = $closeDelay;
        return $this;
    }

    /**
     * Keep the stream open after its last event until the client leaves; closeDelay is then ignored.
     */
    public function keepStreamOpen(bool $keepStreamOpen): self
    {
        $this->keepStreamOpen = $keepStreamOpen;
        return $this;
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    public function primary(bool $primary): self
    {
        $this->primary = $primary;
        return $this;
    }

    /**
     * Template engine used to render the message/event bodies.
     *
     * @param string $templateType one of VELOCITY, JAVASCRIPT, MUSTACHE
     */
    public function templateType(string $templateType): self
    {
        $this->templateType = strtoupper($templateType);
        return $this;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = [];
        if ($this->statusCode !== null) {
            $data['statusCode'] = $this->statusCode;
        }
        if (!empty($this->headers)) {
            $data['headers'] = $this->headers;
        }
        if (!empty($this->events)) {
            $data['events'] = array_map(fn(SseEvent $e) => $e->toArray(), $this->events);
        }
        if ($this->closeConnection !== null) {
            $data['closeConnection'] = $this->closeConnection;
        }
        if ($this->closeDelay !== null) {
            $data['closeDelay'] = $this->closeDelay->toArray();
        }
        if ($this->keepStreamOpen !== null) {
            $data['keepStreamOpen'] = $this->keepStreamOpen;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        if ($this->templateType !== null) {
            $data['templateType'] = $this->templateType;
        }
        return $data;
    }
}
