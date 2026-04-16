package com.tomtom.routing.adapter;

import com.tomtom.routing.exception.ExceptionRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExceptionFileAdapter {

    public static ExceptionRegistry load(Path path) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int commentIdx = trimmed.indexOf('#');
            if (commentIdx > 0) {
                String edgeId = trimmed.substring(0, commentIdx).trim();
                String justification = trimmed.substring(commentIdx + 1).trim();
                entries.put(edgeId, justification);
            } else {
                entries.put(trimmed, "");
            }
        }
        return new ExceptionRegistry(entries);
    }

    public static ExceptionRegistry empty() {
        return new ExceptionRegistry();
    }
}
