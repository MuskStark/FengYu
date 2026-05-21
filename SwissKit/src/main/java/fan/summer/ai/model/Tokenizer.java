package fan.summer.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * BPE (Byte Pair Encoding) tokenizer for GGUF models.
 * Reads vocabulary and merge scores from GGUF metadata.
 */
public class Tokenizer {

    private static final Logger log = LoggerFactory.getLogger(Tokenizer.class);

    private final String[] vocab;
    private final float[] scores;
    private final Map<String, Integer> tokenToId;
    private final int bosToken;
    private final int eosToken;

    public Tokenizer(GGUFModel model) {
        var meta = model.getMetadata();

        Object tokensObj = meta.get("tokenizer.ggml.tokens");
        if (tokensObj instanceof String[] tokensArr) {
            vocab = tokensArr;
        } else {
            throw new RuntimeException("Missing tokenizer.ggml.tokens in model metadata");
        }

        Object scoresObj = meta.get("tokenizer.ggml.scores");
        if (scoresObj instanceof float[] scoresArr) {
            scores = scoresArr;
        } else {
            scores = new float[vocab.length];
            for (int i = 0; i < scores.length; i++) scores[i] = -i;
        }

        tokenToId = new HashMap<>(vocab.length);
        for (int i = 0; i < vocab.length; i++) {
            tokenToId.put(vocab[i], i);
        }

        bosToken = findToken(meta, "tokenizer.ggml.bos_token_id", 1);
        eosToken = findToken(meta, "tokenizer.ggml.eos_token_id", 2);

        log.info("Tokenizer loaded: vocab={}, bos={}, eos={}", vocab.length, bosToken, eosToken);
    }

    public int getBosToken() { return bosToken; }
    public int getEosToken() { return eosToken; }
    public int getVocabSize() { return vocab.length; }
    public String decode(int tokenId) {
        if (tokenId < 0 || tokenId >= vocab.length) return "";
        String piece = vocab[tokenId];
        return decodeBytePiece(piece);
    }

    /**
     * Encode text into token IDs.
     */
    public int[] encode(String text, boolean addBos, boolean addEos) {
        var tokens = new ArrayList<Integer>();
        if (addBos) tokens.add(bosToken);

        if (!text.isEmpty()) {
            String normalized = text;

            // Byte-level tokenization: split into initial tokens
            for (int i = 0; i < normalized.length(); ) {
                int cp = normalized.codePointAt(i);
                String piece = codePointToString(cp);
                Integer id = tokenToId.get(piece);
                if (id != null) {
                    tokens.add(id);
                } else {
                    // UTF-8 byte fallback
                    for (byte b : piece.getBytes(StandardCharsets.UTF_8)) {
                        String bytePiece = byteToToken(b);
                        Integer bid = tokenToId.get(bytePiece);
                        if (bid != null) {
                            tokens.add(bid);
                        } else {
                            tokens.add((int) (b & 0xFF) + 3); // raw byte fallback
                        }
                    }
                }
                i += Character.charCount(cp);
            }

            // BPE merge loop
            while (tokens.size() >= 2) {
                float bestScore = Float.NEGATIVE_INFINITY;
                int bestId = -1;
                int bestPos = -1;

                for (int i = 0; i < tokens.size() - 1; i++) {
                    String merged = vocab[tokens.get(i)] + vocab[tokens.get(i + 1)];
                    Integer mergedId = tokenToId.get(merged);
                    if (mergedId != null && scores[mergedId] > bestScore) {
                        bestScore = scores[mergedId];
                        bestId = mergedId;
                        bestPos = i;
                    }
                }

                if (bestPos == -1) break;
                tokens.set(bestPos, bestId);
                tokens.remove(bestPos + 1);
            }
        }

        if (addEos) tokens.add(eosToken);
        return tokens.stream().mapToInt(Integer::intValue).toArray();
    }

    private String codePointToString(int cp) {
        return new String(Character.toChars(cp));
    }

    private String byteToToken(byte b) {
        return String.format("<0x%02X>", b & 0xFF);
    }

    private String decodeBytePiece(String piece) {
        if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length() == 6) {
            try {
                int val = Integer.parseInt(piece.substring(3, 5), 16);
                return new String(new byte[]{(byte) val}, StandardCharsets.UTF_8);
            } catch (NumberFormatException ignored) {}
        }
        return piece.replace("▁", " ");
    }

    @SuppressWarnings("unchecked")
    private int findToken(Map<String, Object> meta, String key, int defaultVal) {
        Object v = meta.get(key);
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Long) return ((Long) v).intValue();
        return defaultVal;
    }
}
