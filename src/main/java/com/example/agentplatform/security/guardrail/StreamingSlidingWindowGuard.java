package com.example.agentplatform.security.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 32-character sliding window buffer for SSE token streaming.
 * Checks for sensitive word violations across chunk boundaries before sending to client.
 */
public class StreamingSlidingWindowGuard {

    private static final int DEFAULT_WINDOW_SIZE = 32;

    private final SensitiveWordMatcher matcher;
    private final boolean enabled;
    private final boolean blockOnViolation;
    private final int windowSize;
    private final StringBuilder buffer = new StringBuilder();

    private boolean violationDetected = false;
    private String violatedTerm = null;

    public StreamingSlidingWindowGuard(SensitiveWordMatcher matcher, boolean enabled, boolean blockOnViolation, int windowSize) {
        this.matcher = matcher;
        this.enabled = enabled && matcher != null;
        this.blockOnViolation = blockOnViolation;
        this.windowSize = windowSize > 0 ? windowSize : DEFAULT_WINDOW_SIZE;
    }

    public synchronized List<String> processChunk(String chunk) {
        List<String> safeToEmit = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return safeToEmit;
        }
        if (!enabled) {
            safeToEmit.add(chunk);
            return safeToEmit;
        }
        if (violationDetected) {
            return safeToEmit;
        }

        buffer.append(chunk);

        // Check if current buffer contains sensitive word
        Optional<SensitiveWordMatcher.MatchResult> match = matcher.findFirst(buffer.toString());
        if (match.isPresent()) {
            violationDetected = true;
            violatedTerm = match.get().getWord();
            if (blockOnViolation) {
                buffer.setLength(0);
                return safeToEmit;
            } else {
                // If MASK or LOG, mask the buffer
                String masked = matcher.mask(buffer.toString(), '*');
                buffer.setLength(0);
                buffer.append(masked);
            }
        }

        // If buffer length exceeds windowSize, safely emit the prefix
        if (buffer.length() > windowSize) {
            int emitLen = buffer.length() - windowSize;
            String toEmit = buffer.substring(0, emitLen);
            safeToEmit.add(toEmit);
            buffer.delete(0, emitLen);
        }

        return safeToEmit;
    }

    public synchronized String flush() {
        if (!enabled || buffer.length() == 0) {
            String remaining = buffer.toString();
            buffer.setLength(0);
            return remaining;
        }
        if (violationDetected && blockOnViolation) {
            buffer.setLength(0);
            return "";
        }

        Optional<SensitiveWordMatcher.MatchResult> match = matcher.findFirst(buffer.toString());
        if (match.isPresent()) {
            violationDetected = true;
            violatedTerm = match.get().getWord();
            if (blockOnViolation) {
                buffer.setLength(0);
                return "";
            } else {
                String masked = matcher.mask(buffer.toString(), '*');
                buffer.setLength(0);
                return masked;
            }
        }

        String remaining = buffer.toString();
        buffer.setLength(0);
        return remaining;
    }

    public boolean isViolationDetected() {
        return violationDetected;
    }

    public String getViolatedTerm() {
        return violatedTerm;
    }
}
