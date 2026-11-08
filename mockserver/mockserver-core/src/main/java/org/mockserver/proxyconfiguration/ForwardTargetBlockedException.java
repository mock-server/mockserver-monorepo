package org.mockserver.proxyconfiguration;

/**
 * Thrown when {@code forwardProxyBlockPrivateNetworks} refuses a forward or proxy target: it resolves to a blocked
 * address, or cannot be resolved where MockServer runs. Its message names the target and why it was refused. A target
 * refused because it cannot be read as one destination is a {@link MalformedForwardTargetException}.
 */
public class ForwardTargetBlockedException extends IllegalArgumentException {

    public ForwardTargetBlockedException(String message) {
        super(message);
    }

    public ForwardTargetBlockedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @return the refusal {@code failure} is or was caused by, or null
     */
    public static ForwardTargetBlockedException in(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof ForwardTargetBlockedException) {
                return (ForwardTargetBlockedException) failure;
            }
        }
        return null;
    }
}
