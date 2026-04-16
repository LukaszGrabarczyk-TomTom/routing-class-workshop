package com.tomtom.routing.writer;

import com.tomtom.routing.model.*;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ParquetResultWriter implements ResultWriter {

    private static final Schema SCHEMA = SchemaBuilder.record("RcAssignment")
            .namespace("com.tomtom.routing")
            .fields()
            .requiredString("edgeId")
            .optionalInt("routingClass")
            .requiredString("changeType")
            .optionalString("reason")
            .endRecord();

    @Override
    public void write(RcGraph graph, EnforcementReport report, Path outputPath) throws IOException {
        Map<String, RcChange> changeMap = new HashMap<>();
        for (RcChange change : report.changes()) {
            changeMap.put(change.edgeId(), change);
        }

        org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(outputPath.toString());
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hadoopPath)
                .withSchema(SCHEMA)
                .withConf(new Configuration())
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {

            for (Edge edge : graph.edges()) {
                GenericRecord record = new GenericData.Record(SCHEMA);
                record.put("edgeId", edge.id());

                if (edge.routingClass().isPresent()) {
                    record.put("routingClass", edge.routingClass().getAsInt());
                } else {
                    record.put("routingClass", null);
                }

                RcChange change = changeMap.get(edge.id());
                if (change != null) {
                    String changeType = change.reason() == RcChange.Reason.UPGRADE ? "upgraded" : "downgraded";
                    record.put("changeType", changeType);
                    record.put("reason", change.context());
                } else {
                    record.put("changeType", "unchanged");
                    record.put("reason", null);
                }

                writer.write(record);
            }
        }
    }
}
