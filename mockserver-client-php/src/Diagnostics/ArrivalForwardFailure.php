<?php

declare(strict_types=1);

namespace MockServer\Diagnostics;

/**
 * Why a request's forward failed, so MockServer answered 502: the same facts
 * the explain_forward_failure MCP tool gives.
 */
final class ArrivalForwardFailure
{
    /**
     * @param int|null $statusCode The status MockServer answered with (502).
     * @param string|null $reason Why the forward failed, redacted as the event log is and capped at 2048 characters.
     * @param bool $reasonTruncated Whether the reason was capped.
     * @param bool|null $causeNamed Whether MockServer named the cause; when false the reason is the raw error.
     * @param bool|null $forwardLoopSuspected Whether the forward appears to have gone back to MockServer itself.
     * @param string|null $hint What to look at next; set when the cause is not named.
     */
    public function __construct(
        public readonly ?int $statusCode,
        public readonly ?string $reason,
        public readonly bool $reasonTruncated,
        public readonly ?bool $causeNamed,
        public readonly ?bool $forwardLoopSuspected,
        public readonly ?string $hint,
    ) {
    }

    /**
     * @param array<string, mixed> $data The decoded 'atArrival.forwardFailure' object of an explain response.
     */
    public static function fromArray(array $data): self
    {
        return new self(
            Fields::int($data, 'statusCode'),
            Fields::string($data, 'reason'),
            Fields::bool($data, 'reasonTruncated') ?? false,
            Fields::bool($data, 'causeNamed'),
            Fields::bool($data, 'forwardLoopSuspected'),
            Fields::string($data, 'hint'),
        );
    }
}
