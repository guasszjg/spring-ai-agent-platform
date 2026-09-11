package com.example.agentplatform.security.guardrail;

import java.util.*;

/**
 * High-performance multi-pattern sensitive word matcher based on Aho-Corasick (AC) Automaton.
 * Guarantees O(N) linear scan time regardless of dictionary size.
 */
public class SensitiveWordMatcher {

    private final Node root;

    public static class MatchResult {
        private final int start;
        private final int end;
        private final String word;

        public MatchResult(int start, int end, String word) {
            this.start = start;
            this.end = end;
            this.word = word;
        }

        public int getStart() { return start; }
        public int getEnd() { return end; }
        public String getWord() { return word; }
    }

    private static class Node {
        final Map<Character, Node> next = new HashMap<>();
        Node fail;
        final List<String> outputs = new ArrayList<>();
    }

    public SensitiveWordMatcher(Collection<String> words) {
        this.root = new Node();
        if (words != null) {
            for (String w : words) {
                if (w != null && !w.trim().isEmpty()) {
                    insert(w.trim().toLowerCase());
                }
            }
        }
        buildFailPointers();
    }

    private void insert(String word) {
        Node curr = root;
        for (char c : word.toCharArray()) {
            curr = curr.next.computeIfAbsent(c, k -> new Node());
        }
        curr.outputs.add(word);
    }

    private void buildFailPointers() {
        Queue<Node> queue = new LinkedList<>();
        for (Node child : root.next.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node curr = queue.poll();
            for (Map.Entry<Character, Node> entry : curr.next.entrySet()) {
                char ch = entry.getKey();
                Node child = entry.getValue();
                Node failNode = curr.fail;
                while (failNode != null && !failNode.next.containsKey(ch)) {
                    failNode = failNode.fail;
                }
                child.fail = (failNode != null) ? failNode.next.get(ch) : root;
                child.outputs.addAll(child.fail.outputs);
                queue.add(child);
            }
        }
    }

    public Optional<MatchResult> findFirst(String text) {
        if (text == null || text.isEmpty()) return Optional.empty();
        String lower = text.toLowerCase();
        Node curr = root;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            while (curr != root && !curr.next.containsKey(ch)) {
                curr = curr.fail;
            }
            curr = curr.next.getOrDefault(ch, root);
            if (!curr.outputs.isEmpty()) {
                String matched = curr.outputs.get(0);
                return Optional.of(new MatchResult(i - matched.length() + 1, i + 1, matched));
            }
        }
        return Optional.empty();
    }

    public List<MatchResult> findAll(String text) {
        List<MatchResult> results = new ArrayList<>();
        if (text == null || text.isEmpty()) return results;
        String lower = text.toLowerCase();
        Node curr = root;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            while (curr != root && !curr.next.containsKey(ch)) {
                curr = curr.fail;
            }
            curr = curr.next.getOrDefault(ch, root);
            for (String matched : curr.outputs) {
                results.add(new MatchResult(i - matched.length() + 1, i + 1, matched));
            }
        }
        return results;
    }

    public String mask(String text, char maskChar) {
        if (text == null || text.isEmpty()) return text;
        List<MatchResult> matches = findAll(text);
        if (matches.isEmpty()) return text;

        char[] chars = text.toCharArray();
        for (MatchResult m : matches) {
            for (int i = m.getStart(); i < m.getEnd() && i < chars.length; i++) {
                chars[i] = maskChar;
            }
        }
        return new String(chars);
    }
}
