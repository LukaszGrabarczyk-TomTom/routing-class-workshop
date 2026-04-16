# Routing Class Conflation — Operational Runbook

> **Purpose:** Step-by-step guide for running and monitoring the weekly Routing Class conflation process.

---

## Prerequisites

- Access to Databricks workspace (prod: `adb-132835279355663.3.azuredatabricks.net`)
- Access to Spring Dataflow (prod: `https://dataflow.inc-conf.prod.maps-amf.tomtom.com`)
- Access to Grafana dashboards
- Access to Azure blob storage (prod: `navconfprod.blob.core.windows.net/routing-class`)
- Kafka access for monitoring consumer lag

---

## 1. Wait for FRC Delivery

### Trigger

The process is triggered by receiving input data: CSV files with calculated routing classes.

**Location:** Azure blob container `routing-class` under a `YYYY_MM_DD` directory.

Inside the directory: 3 xz archives:
```
Orbis_NDS_Map_Test_<YYYY_MM_DD>_<area>.pbf_rc.xz
```
Areas: `afroeurope`, `americas`, `asiaoceania`

Each archive contains CSV with columns: `ProductId, Net2Class, CountryCode`

**Notification:** Slack notification from `routing-api-slack-notifier` to #osprey-team with:
- Folder name (delivery date)
- NexVentura release version

### FRC Contacts

- EM: Alina Ushakova
- SE: Vladimir Shapranov (backups: Manjusree, Simon Rotter)
- Slack: #directions-routing-aether-public

---

## 2. Run Databricks Conflation Job

### Job Name: `meron-rc-conflation-file-generation`

Navigate to the Databricks workspace and locate the job.

### Parameters to Configure

| Parameter | Description | Example |
|-----------|-------------|---------|
| `deliveryDate` | Date from the delivery folder | `2026_04_09` |
| `rc_release` | NDS release used for FRC | `ON_26140.000` |
| `release` | NexVentura release version | `2026-03-30` |
| `KafkaTopicName` | Kafka topic for output | `<date>_full_world_<env>` |
| `mount` | Storage mount point | (environment-specific) |
| `nexventura` | NexVentura release ID | (from notification) |
| `routingClassLayerRevision` | Current layer revision | e.g., `16971:13751249` |

### Job Steps (Sequential)

1. **CreateIdMapping** — Reads 3S mapping and Format Conversion ID mapping to build ProductId → OrbisId lookup
2. **UnzipDeliveryFiles** — Decompresses XZ-compressed FRC delivery files
3. **CreateSnapshot** — Creates a snapshot of the latest Routing Class layer state
4. **MergeAndDeduplicate** (`generate_conflation_file`) — Joins routing classes with ID mapping
5. **FilteringSameValues** (`routing_class_filtering`) — Removes entries where RC value hasn't changed
6. **SendRCToKafka** — Publishes filtered records to Kafka topic

### Verification After Databricks Job

Check results from `generate_conflation_file` and `routing_class_filtering` notebooks:
- Verify merge counts (how many IDs matched)
- Verify filtering counts (how many unchanged values were removed)
- Verify Kafka topic message counts

**Typical timing:** ~41 minutes (as of April 2026)

---

## 3. Run Spring Dataflow Conflation Stream

### Stream Name: `merone-rc-conflation`

Navigate to Spring Dataflow and configure:

| Setting | Value |
|---------|-------|
| Topic name | Same as `KafkaTopicName` from step 2 |
| Delivery revision | Current layer revision |
| Application version | Latest version (check releases) |

Deploy the stream.

### Monitoring

| Channel | What to Check |
|---------|---------------|
| **Spring Dataflow logs** | Stream execution status |
| **Kubernetes/OpenLens** | Pod health and resource usage |
| **Kafka consumer lag** | Messages remaining to process |
| **Grafana hosted logs** | Application-level logging |
| **Grafana RoutingClass Conflation Dashboard** | End-to-end metrics |

### Key Events to Watch

| Event | Meaning |
|-------|---------|
| `RoutingClassTagAddedEvent` | RC value successfully applied |
| `NoTransportationLineFoundEvent` | Could not find road for ProductId (expected for ~0.1% of delivery) |
| `FeedbackOverwrittenEvent` | Conflation overwrote an older feedback value |
| `RoutingClassTagSkippedEvent` | Value unchanged, skipped |
| `RoutingClassTagDeletedEvent` | Old value removed |

**Typical timing:** ~3 minutes (as of April 2026)

---

## 4. Post-Conflation

### Undeploy the Stream

**Critical:** Undeploy the `merone-rc-conflation` stream after completion. Leaving it running burns money.

### Update Documentation

Update the [Conflation history Prod](https://tomtom.atlassian.net/wiki/spaces/ORBR/pages/789333045/Conflation+history+Prod) Confluence page with:

| Field | Description |
|-------|-------------|
| Date | Conflation date |
| Delivery | Delivery folder name |
| NexVentura Release | Release version |
| NDS | NDS build date |
| Delivery revision | Layer revision used |
| Snapshot revision | Snapshot revision created |
| Dashboard link | Grafana link |
| Stream run | Run number |
| FRC Notification | When notification was received |
| Start / End / Time | Timing details |
| Kafka Messages | Total messages processed |
| Version | Application version used |
| Pods | Number of pods |
| Batch | Batch size |

---

## 5. Troubleshooting

### High NoTransportationLineFoundEvent Count

- Expected: ~0.1% of total delivery (~344K for ~313M delivery)
- If significantly higher: check IDMHB freshness, verify delivery date alignment with OPC

### Kafka Consumer Lag Not Decreasing

- Check pod health in Kubernetes
- Check for DLT (Dead Letter Topic) messages — DLT reprocessing is **manual only**
- Verify Kafka connectivity

### Conflation Application Retry Failures

- `CustomRetryListener` provides exponential backoff for transient failures
- Check GSS API availability
- Check ODP (Orbis Data Platform) availability

### Layer Revision Mismatch

- `delivery.revision` must be the current revision at the time of conflation launch
- If the Transformation Service has advanced the revision, the conflation may produce stale overrides

---

## 6. Environment Reference

| | Dev | Prod |
|---|-----|------|
| **Layer ID** | 29125 | 16971 |
| **Databricks** | `adb-1791661381914295.15.azuredatabricks.net` | `adb-132835279355663.3.azuredatabricks.net` |
| **Spring Dataflow** | — | `https://dataflow.inc-conf.prod.maps-amf.tomtom.com` |
| **Storage** | `saroutingclassweudev.blob.core.windows.net/routingclass` | `navconfprod.blob.core.windows.net/routing-class` |
| **Azure Subscription** | orbis-routing-dev | maps-amf-production |
| **K8s Cluster** | aks-routing-class-dev | aks-amf-incremental-conflation-prod |

---

*Last updated: 2026-04-16*
