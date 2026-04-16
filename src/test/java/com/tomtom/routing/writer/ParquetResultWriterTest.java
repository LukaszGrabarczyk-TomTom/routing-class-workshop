package com.tomtom.routing.writer;

import com.tomtom.routing.model.*;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ParquetResultWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void writesAllEdgesWithCorrectSchema() throws IOException {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addNode(new Node("n3"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "n2", "n3", TraversalMode.BOTH, 2));

        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e2", 3, 2, RcChange.Reason.DOWNGRADE, "island at RC1"));

        Path output = tempFolder.getRoot().toPath().resolve("result.parquet");
        new ParquetResultWriter().write(graph, report, output);

        List<GenericRecord> records = readParquet(output);
        assertEquals(2, records.size());

        GenericRecord r1 = records.stream()
                .filter(r -> r.get("edgeId").toString().equals("e1"))
                .findFirst().orElseThrow();
        assertEquals(1, r1.get("routingClass"));
        assertEquals("unchanged", r1.get("changeType").toString());

        GenericRecord r2 = records.stream()
                .filter(r -> r.get("edgeId").toString().equals("e2"))
                .findFirst().orElseThrow();
        assertEquals(2, r2.get("routingClass"));
        assertEquals("downgraded", r2.get("changeType").toString());
    }

    @Test
    public void edgesWithoutRcWrittenAsNull() throws IOException {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH));

        Path output = tempFolder.getRoot().toPath().resolve("result.parquet");
        new ParquetResultWriter().write(graph, new EnforcementReport(), output);

        List<GenericRecord> records = readParquet(output);
        assertEquals(1, records.size());
        assertNull(records.get(0).get("routingClass"));
    }

    private List<GenericRecord> readParquet(Path path) throws IOException {
        List<GenericRecord> records = new ArrayList<>();
        Configuration conf = new Configuration();
        org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(path.toString());
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(hadoopPath)
                .withConf(conf).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                records.add(record);
            }
        }
        return records;
    }
}
