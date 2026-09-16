package com.redis.workshop.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.service.LocalEmbeddingService;
import com.redis.workshop.tools.PdfChunker;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@DependsOn("startupCleanup")
public class DocumentDataLoader {

    private static final Logger log = LoggerFactory.getLogger(DocumentDataLoader.class);
    private static final String DOC_PREFIX = "uc8:doc:";
    private static final String INDEX_NAME = "idx:uc8:documents";
    private static final int VECTOR_DIM = 384;
    private static final String EMBEDDINGS_RESOURCE = "/data/kb-embeddings.json";
    private static final String EMBEDDINGS_WRITE_PATH = "src/main/resources/data/kb-embeddings.json";

    /** Regulation PDFs expected under /docs/ on the classpath. */
    private static final List<PdfSource> PDF_SOURCES = List.of(
            new PdfSource("psd2", "PSD2 - Payment Services Directive 2"),
            new PdfSource("dora", "DORA - Digital Operational Resilience Act"),
            new PdfSource("mifid2", "MiFID II - Markets in Financial Instruments Directive"),
            new PdfSource("gdpr", "GDPR - General Data Protection Regulation"),
            new PdfSource("euaiact", "EU AI Act - Artificial Intelligence Act")
    );

    private record PdfSource(String id, String title) {}

    private final StringRedisTemplate redis;
    private final LocalEmbeddingService localEmbeddingService;

    @Value("${workshop.startup.load-data:true}")
    private boolean loadData;

    @Value("${workshop.startup.force-reload:false}")
    private boolean forceReload;

    public DocumentDataLoader(StringRedisTemplate redis, LocalEmbeddingService localEmbeddingService) {
        this.redis = redis;
        this.localEmbeddingService = localEmbeddingService;
    }

    @PostConstruct
    public void loadDocuments() {
        if (!loadData) return;
        if (forceReload) {
            log.info("UC8: force reload enabled for regulation documents, rebuilding index and JSON docs");
        } else {
            long existingDocs = existingDocCount();
            if (existingDocs >= 1 && !RedisStartupHelper.indexExists(redis, INDEX_NAME)) {
                log.warn("UC8: document data present but index {} is missing, rebuilding", INDEX_NAME);
                existingDocs = 0;
            }
            if (existingDocs >= 1) {
                int existingVectorDim = existingStoredVectorDimension();
                if (existingVectorDim == VECTOR_DIM) {
                    log.info("UC8: document index already present ({} docs, {}-dim vectors), skipping reload",
                            existingDocs, existingVectorDim);
                    return;
                }
                log.info("UC8: document data present but vector dimension is {} (expected {}), rebuilding",
                        existingVectorDim, VECTOR_DIM);
            }
        }

        List<Map<String, Object>> docs;

        // 1. Try loading pre-computed embeddings from classpath first
        List<Map<String, Object>> precomputed = loadPrecomputedEmbeddings();
        if (precomputed != null && !precomputed.isEmpty()) {
            docs = precomputed;
            log.info("UC6: Using {} pre-computed document chunks from kb-embeddings.json", docs.size());
        } else {
            // 2. No embeddings file — try to auto-generate from classpath PDFs
            log.info("UC6: No pre-computed embeddings found, attempting to auto-generate from PDFs...");
            List<Map<String, Object>> generated = generateEmbeddingsFromPdfs();
            if (generated != null && !generated.isEmpty()) {
                docs = generated;
                log.info("UC6: Auto-generated {} document chunks from PDFs", docs.size());
            } else {
                // 3. No PDFs either — fall back to hand-crafted mock documents
                log.info("UC6: No PDFs found under /docs/, using built-in mock documents");
                docs = buildMockDocuments();
            }
        }

        // Store each document as JSON
        for (Map<String, Object> doc : docs) {
            String key = DOC_PREFIX + doc.get("id");
            String json = toJson(doc);
            redis.execute(connection -> {
                connection.execute("JSON.SET",
                        key.getBytes(StandardCharsets.UTF_8),
                        "$".getBytes(StandardCharsets.UTF_8),
                        json.getBytes(StandardCharsets.UTF_8));
                return null;
            }, true);
        }

        // Create RediSearch index
        createIndex();
        log.info("UC6: Loaded {} regulation documents with vector embeddings", docs.size());
    }

    private long existingDocCount() {
        try {
            return Math.max(
                    RedisStartupHelper.indexDocCount(redis, INDEX_NAME),
                    RedisStartupHelper.countKeys(redis, DOC_PREFIX + "*")
            );
        } catch (Exception e) {
            return RedisStartupHelper.countKeys(redis, DOC_PREFIX + "*");
        }
    }

    private int existingStoredVectorDimension() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, DOC_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return -1;
        }
        return RedisStartupHelper.jsonVectorDimension(redis, keys.iterator().next());
    }

    /**
     * Load pre-computed embeddings from /data/kb-embeddings.json on the classpath.
     * Returns null if the file is not found or cannot be parsed.
     */
    private List<Map<String, Object>> loadPrecomputedEmbeddings() {
        try {
            InputStream is = getClass().getResourceAsStream(EMBEDDINGS_RESOURCE);
            if (is == null) {
                return null;
            }
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> chunks = mapper.readValue(is, new TypeReference<>() {});
            if (chunks == null || chunks.isEmpty()) {
                return null;
            }
            if (!hasExpectedVectorDimensions(chunks)) {
                log.info("UC8: Ignoring pre-computed embeddings with mismatched vector dimensions (expected {})",
                        VECTOR_DIM);
                return null;
            }
            return convertChunksToDocs(chunks);
        } catch (Exception e) {
            log.warn("UC6: Failed to load pre-computed embeddings: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Auto-generate document chunks with local BGE embeddings from the PDFs bundled under /docs/.
     * Writes the result to kb-embeddings.json on a best-effort basis (dev mode only).
     * Returns null if no PDFs are available.
     */
    private List<Map<String, Object>> generateEmbeddingsFromPdfs() {
        List<Map<String, Object>> chunks = new ArrayList<>();
        int pdfCount = 0;
        for (PdfSource pdf : PDF_SOURCES) {
            try (InputStream is = getClass().getResourceAsStream("/docs/" + pdf.id() + ".pdf")) {
                if (is == null) {
                    log.debug("UC6: PDF not found on classpath: /docs/{}.pdf", pdf.id());
                    continue;
                }
                byte[] bytes = is.readAllBytes();
                log.info("UC6: Chunking {} ({} KB)...", pdf.id(), bytes.length / 1024);
                List<Map<String, String>> pdfChunks = PdfChunker.chunkPdf(bytes, pdf.id(), pdf.title());
                if (pdfChunks.isEmpty()) continue;
                pdfCount++;
                for (Map<String, String> chunk : pdfChunks) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", chunk.get("id"));
                    entry.put("title", chunk.get("title"));
                    entry.put("source", chunk.get("source"));
                    entry.put("chunkIndex", chunk.get("chunkIndex"));
                    entry.put("content", chunk.get("content"));
                    chunks.add(entry);
                }
            } catch (Exception e) {
                log.warn("UC6: Failed to process PDF {}: {}", pdf.id(), e.getMessage());
            }
        }
        if (chunks.isEmpty()) return null;
        log.info("UC6: Generated {} chunks from {} PDFs", chunks.size(), pdfCount);

        log.info("UC6: Generating local BGE embeddings for {} PDF chunks...", chunks.size());
        List<String> texts = chunks.stream()
                .map(c -> c.getOrDefault("title", "") + " " + c.getOrDefault("content", ""))
                .map(Object::toString)
                .toList();
        List<float[]> allEmbeddings = new ArrayList<>();
        int totalBatches = (texts.size() + 499) / 500;
        for (int i = 0; i < texts.size(); i += 500) {
            List<String> batch = texts.subList(i, Math.min(i + 500, texts.size()));
            log.info("UC6: Embedding batch {}/{} ({} chunks)...",
                    (i / 500) + 1, totalBatches, batch.size());
            allEmbeddings.addAll(localEmbeddingService.getEmbeddings(batch));
        }
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).put("vector", allEmbeddings.get(i));
        }
        log.info("UC6: Using local BGE embeddings for {} PDF chunks", chunks.size());

        tryWriteEmbeddings(chunks);

        return convertChunksToDocs(chunks);
    }

    /** Convert raw chunk maps (with id/title/source/content/vector) into the loader's doc format. */
    private List<Map<String, Object>> convertChunksToDocs(List<Map<String, Object>> chunks) {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", chunk.get("id"));
            doc.put("title", chunk.get("title"));
            String source = chunk.getOrDefault("source", "").toString().toUpperCase();
            doc.put("category", source);
            doc.put("tags", source.toLowerCase());
            String content = chunk.getOrDefault("content", "").toString();
            doc.put("summary", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            doc.put("content", content);

            Object vectorObj = chunk.get("vector");
            if (vectorObj instanceof float[] arr) {
                doc.put("vector", arr);
            } else if (vectorObj instanceof List<?> vectorList && !vectorList.isEmpty()) {
                float[] vector = new float[vectorList.size()];
                for (int i = 0; i < vectorList.size(); i++) {
                    vector[i] = ((Number) vectorList.get(i)).floatValue();
                }
                doc.put("vector", vector);
            } else {
                doc.put("vector", localEmbeddingService.getEmbedding(content));
            }
            docs.add(doc);
        }
        return docs;
    }

    private boolean hasExpectedVectorDimensions(List<Map<String, Object>> chunks) {
        for (Map<String, Object> chunk : chunks) {
            if (vectorDimension(chunk.get("vector")) != VECTOR_DIM) {
                return false;
            }
        }
        return true;
    }

    private int vectorDimension(Object vectorObj) {
        if (vectorObj instanceof float[] arr) {
            return arr.length;
        }
        if (vectorObj instanceof List<?> vectorList) {
            return vectorList.size();
        }
        return -1;
    }

    /** Best-effort write of generated chunks to kb-embeddings.json. No-op if write target is unavailable (e.g. JAR). */
    private void tryWriteEmbeddings(List<Map<String, Object>> chunks) {
        try {
            File target = new File(EMBEDDINGS_WRITE_PATH);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            if (parent == null || !parent.isDirectory()) {
                log.debug("UC6: Skipping embeddings write ({} not available)", EMBEDDINGS_WRITE_PATH);
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(target, chunks);
            log.info("UC6: Saved generated embeddings to {}", target.getAbsolutePath());
        } catch (Exception e) {
            log.warn("UC6: Failed to write kb-embeddings.json: {}", e.getMessage());
        }
    }

    private void createIndex() {
        try {
            // Drop existing index if present
            redis.execute(connection -> {
                try {
                    connection.execute("FT.DROPINDEX",
                            INDEX_NAME.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
                return null;
            }, true);
        } catch (Exception ignored) {}

        redis.execute(connection -> {
            connection.execute("FT.CREATE",
                    INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                    "ON".getBytes(StandardCharsets.UTF_8),
                    "JSON".getBytes(StandardCharsets.UTF_8),
                    "PREFIX".getBytes(StandardCharsets.UTF_8),
                    "1".getBytes(StandardCharsets.UTF_8),
                    DOC_PREFIX.getBytes(StandardCharsets.UTF_8),
                    "SCHEMA".getBytes(StandardCharsets.UTF_8),
                    "$.title".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "title".getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    "WEIGHT".getBytes(StandardCharsets.UTF_8),
                    "2.0".getBytes(StandardCharsets.UTF_8),
                    "$.category".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "category".getBytes(StandardCharsets.UTF_8),
                    "TAG".getBytes(StandardCharsets.UTF_8),
                    "$.summary".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "summary".getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    "$.content".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "content".getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    "$.tags".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "tags".getBytes(StandardCharsets.UTF_8),
                    "TAG".getBytes(StandardCharsets.UTF_8),
                    "SEPARATOR".getBytes(StandardCharsets.UTF_8),
                    ",".getBytes(StandardCharsets.UTF_8),
                    "$.vector".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "vector".getBytes(StandardCharsets.UTF_8),
                    "VECTOR".getBytes(StandardCharsets.UTF_8),
                    "HNSW".getBytes(StandardCharsets.UTF_8),
                    "6".getBytes(StandardCharsets.UTF_8),
                    "TYPE".getBytes(StandardCharsets.UTF_8),
                    "FLOAT32".getBytes(StandardCharsets.UTF_8),
                    "DIM".getBytes(StandardCharsets.UTF_8),
                    String.valueOf(VECTOR_DIM).getBytes(StandardCharsets.UTF_8),
                    "DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8),
                    "COSINE".getBytes(StandardCharsets.UTF_8));
            return null;
        }, true);
    }

    /**
     * Hand-crafted fallback when neither kb-embeddings.json nor PDF files are available.
     * Produces 5 regulations (one per PDF expected under /docs/) with 3 chunks each,
     * using chunk-style IDs ({source}:chunk:{n}) so the UI dropdowns resolve the same way
     * as the PDF-derived path. Vectors are generated with the local BGE model.
     */
    private List<Map<String, Object>> buildMockDocuments() {
        List<Map<String, Object>> chunks = new ArrayList<>();

        // --- PSD2 ---
        chunks.add(mockChunk("psd2", 0, "PSD2 - Payment Services Directive 2",
                "Strong Customer Authentication (SCA) is mandatory under PSD2 for all electronic payment transactions within the EEA exceeding 30 EUR. SCA requires at least two of three factors: knowledge (password or PIN), possession (mobile device or card), and inherence (fingerprint or facial recognition). Payment service providers must implement SCA by default, with limited exemptions for low-value transactions, recurring payments, and trusted beneficiaries."));
        chunks.add(mockChunk("psd2", 1, "PSD2 - Payment Services Directive 2",
                "Account Servicing Payment Service Providers (ASPSPs) must provide dedicated APIs for Third Party Providers (TPPs) to access customer account information and initiate payments with explicit customer consent. APIs must support Account Information Services (AIS) and Payment Initiation Services (PIS), maintain 99.5% availability and deliver sub-500ms response times for account data queries. Banks cannot discriminate against TPP requests."));
        chunks.add(mockChunk("psd2", 2, "PSD2 - Payment Services Directive 2",
                "Under PSD2 payment service providers bear liability for unauthorized transactions unless the payer acted fraudulently or with gross negligence. The payer's maximum liability for unauthorized transactions is limited to 50 EUR provided they notify their bank without undue delay. Banks must implement real-time transaction monitoring, behavioral analytics and device fingerprinting to detect and prevent fraud, and report fraud statistics to competent authorities semi-annually."));

        // --- DORA ---
        chunks.add(mockChunk("dora", 0, "DORA - Digital Operational Resilience Act",
                "DORA establishes a harmonised ICT risk management framework for financial entities across the EU. Firms must implement sound, comprehensive and well-documented ICT risk management, including governance, identification of ICT risk, protection and prevention, detection of anomalies, response and recovery, learning and evolving, and communication. The management body holds ultimate responsibility and must approve and periodically review the ICT risk framework."));
        chunks.add(mockChunk("dora", 1, "DORA - Digital Operational Resilience Act",
                "Financial entities must classify ICT-related incidents by severity and report major incidents to their competent authority within strict time windows: an initial notification within 4 hours of classification, an intermediate report within 72 hours, and a final report within one month. Threat-led penetration testing (TLPT) is required at least every three years for significant entities, covering critical functions and third-party providers."));
        chunks.add(mockChunk("dora", 2, "DORA - Digital Operational Resilience Act",
                "DORA imposes strict rules on ICT third-party risk: financial entities must maintain a register of information on all contractual arrangements, perform risk assessments before entering any contract, and include mandatory clauses covering service levels, audit rights, subcontracting, exit strategies and cooperation with supervisors. Critical ICT third-party providers are subject to direct oversight by European Supervisory Authorities with powers to issue recommendations and fines."));

        // --- MiFID II ---
        chunks.add(mockChunk("mifid2", 0, "MiFID II - Markets in Financial Instruments Directive",
                "MiFID II requires investment firms to report detailed information on all transactions in financial instruments to the relevant competent authority by the end of the following working day (T+1). Each transaction report must include 65 data fields covering client identification, instrument details, price, quantity, and execution venue. Firms must retain records for at least five years and implement systematic monitoring for suspicious trading patterns."));
        chunks.add(mockChunk("mifid2", 1, "MiFID II - Markets in Financial Instruments Directive",
                "Investment firms must take all sufficient steps to achieve the best possible result for their clients when executing orders, considering price, costs, speed, likelihood of execution and settlement, size, nature and any other relevant consideration. Firms must establish and implement an order execution policy, monitor execution quality across venues and publish annual RTS 28 reports on the top five execution venues by trading volume for each class of instrument."));
        chunks.add(mockChunk("mifid2", 2, "MiFID II - Markets in Financial Instruments Directive",
                "MiFID II reinforces investor protection with product governance rules: manufacturers must define a target market for each product, distributors must assess suitability and appropriateness, and firms must provide clear ex-ante and ex-post cost disclosures. Inducements from third parties are prohibited for independent advice and portfolio management. Retail clients receive additional protections including enhanced information disclosures and restrictions on complex product sales."));

        // --- GDPR ---
        chunks.add(mockChunk("gdpr", 0, "GDPR - General Data Protection Regulation",
                "The GDPR requires controllers to have a lawful basis for processing personal data (Art. 6): consent, contract, legal obligation, vital interests, public interest or legitimate interests. Data subjects have rights to access, rectification, erasure (right to be forgotten), restriction, data portability and objection. Banks must implement appropriate technical and organisational measures including encryption, access controls and data minimisation. Personal data breaches must be reported to the supervisory authority within 72 hours."));
        chunks.add(mockChunk("gdpr", 1, "GDPR - General Data Protection Regulation",
                "Transfers of personal data outside the European Economic Area are restricted unless the receiving country has an adequacy decision from the European Commission, or appropriate safeguards such as Standard Contractual Clauses (SCCs) or Binding Corporate Rules (BCRs) are in place. Following the Schrems II judgment controllers must perform Transfer Impact Assessments (TIAs) and may need supplementary technical measures such as strong encryption and pseudonymisation."));
        chunks.add(mockChunk("gdpr", 2, "GDPR - General Data Protection Regulation",
                "Article 22 of the GDPR grants individuals the right not to be subject to a decision based solely on automated processing, including profiling, which produces legal effects concerning them or similarly significantly affects them. Banks using automated credit scoring or risk profiling must ensure customers can obtain human intervention, express their point of view and contest decisions. A Data Protection Impact Assessment (DPIA) is mandatory before deploying high-risk automated processing."));

        // --- EU AI Act ---
        chunks.add(mockChunk("euaiact", 0, "EU AI Act - Artificial Intelligence Act",
                "The EU AI Act adopts a risk-based approach, classifying AI systems into four categories: unacceptable risk (prohibited, e.g. social scoring by public authorities), high risk (subject to strict requirements), limited risk (transparency obligations) and minimal risk (voluntary codes of conduct). The Act applies extraterritorially to providers and deployers whose AI outputs are used within the Union, with fines up to 35 million EUR or 7% of global annual turnover for prohibited practices."));
        chunks.add(mockChunk("euaiact", 1, "EU AI Act - Artificial Intelligence Act",
                "AI systems used for creditworthiness assessment of natural persons, risk assessment in life and health insurance pricing, and evaluation of eligibility for essential private and public services are classified as high-risk. Providers must implement a risk management system, ensure data governance and quality, keep technical documentation, enable automatic logging, provide transparency to deployers, ensure human oversight, and achieve appropriate accuracy, robustness and cybersecurity before CE marking and registration in the EU database."));
        chunks.add(mockChunk("euaiact", 2, "EU AI Act - Artificial Intelligence Act",
                "Deployers of high-risk AI systems in financial services must assign human oversight to competent individuals, monitor the operation of the system, keep logs for at least six months, and inform natural persons when they are subject to automated decisions. Providers of general-purpose AI models must publish training data summaries and comply with EU copyright law. Banks using generative AI for customer communications must disclose the AI-generated nature of content."));

        log.info("UC6: Generating local BGE embeddings for {} fallback chunks...", chunks.size());
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> c : chunks) {
            texts.add(c.get("title") + " " + c.get("content"));
        }
        List<float[]> embeddings = localEmbeddingService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).put("vector", embeddings.get(i));
        }

        return convertChunksToDocs(chunks);
    }

    private static Map<String, Object> mockChunk(String source, int idx, String title, String content) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", source + ":chunk:" + idx);
        chunk.put("title", title);
        chunk.put("source", source);
        chunk.put("chunkIndex", String.valueOf(idx));
        chunk.put("content", content);
        return chunk;
    }

    /** Convert float array to JSON array string. */
    private static String vectorToJsonArray(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** Simple JSON serialization (no external dependency). */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            Object val = e.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (val instanceof float[] arr) {
                sb.append(vectorToJsonArray(arr));
            } else {
                sb.append(val);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
