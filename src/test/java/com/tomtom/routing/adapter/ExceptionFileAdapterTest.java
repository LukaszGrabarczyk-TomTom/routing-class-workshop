package com.tomtom.routing.adapter;

import com.tomtom.routing.exception.ExceptionRegistry;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ExceptionFileAdapterTest {

    @Test
    public void parsesSampleFile() throws IOException {
        Path path = Path.of("src/test/resources/exceptions-sample.txt");
        ExceptionRegistry registry = ExceptionFileAdapter.load(path);

        assertTrue(registry.isException("e100"));
        assertTrue(registry.isException("e101"));
        assertTrue(registry.isException("e200"));
        assertFalse(registry.isException("e999"));
        assertEquals(3, registry.size());
    }

    @Test
    public void skipsBlankLinesAndCommentOnlyLines() throws IOException {
        Path path = Path.of("src/test/resources/exceptions-sample.txt");
        ExceptionRegistry registry = ExceptionFileAdapter.load(path);
        assertEquals(3, registry.size());
    }

    @Test
    public void emptyRegistryWhenPathIsNull() {
        ExceptionRegistry registry = ExceptionFileAdapter.empty();
        assertFalse(registry.isException("e100"));
        assertEquals(0, registry.size());
    }

    @Test
    public void justificationIsAvailable() throws IOException {
        Path path = Path.of("src/test/resources/exceptions-sample.txt");
        ExceptionRegistry registry = ExceptionFileAdapter.load(path);

        assertEquals("Nordkapp peninsula", registry.justification("e100").orElse(""));
        assertEquals("Gibraltar terminus", registry.justification("e101").orElse(""));
    }
}
