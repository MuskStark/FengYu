# LLaMA Java — 纯 Java 本地大模型推理引擎实现计划

> 目标：使用纯 Java 21 实现一个可在本地运行小型语言模型（如 LLaMA 3、Qwen、Mistral）的推理引擎，支持命令行交互，媲美 llama.cpp 的功能体验。

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术选型与依赖](#2-技术选型与依赖)
3. [整体架构设计](#3-整体架构设计)
4. [详细模块实现计划](#4-详细模块实现计划)
   - 4.1 [GGUF 文件解析器](#41-gguf-文件解析器)
   - 4.2 [量化张量系统](#42-量化张量系统)
   - 4.3 [BPE 分词器](#43-bpe-分词器)
   - 4.4 [Transformer 推理引擎](#44-transformer-推理引擎)
   - 4.5 [KV Cache](#45-kv-cache)
   - 4.6 [采样器](#46-采样器)
   - 4.7 [Chat Template 处理器](#47-chat-template-处理器)
   - 4.8 [CLI 交互界面](#48-cli-交互界面)
5. [性能优化策略](#5-性能优化策略)
6. [阶段性开发计划](#6-阶段性开发计划)
7. [测试与验证方案](#7-测试与验证方案)
8. [支持的模型列表](#8-支持的模型列表)
9. [项目结构](#9-项目结构)
10. [构建与运行](#10-构建与运行)
11. [里程碑与交付物](#11-里程碑与交付物)

---

## 1. 项目概述

### 1.1 背景

[llama.cpp](https://github.com/ggerganov/llama.cpp) 是目前最流行的本地 LLM 推理框架，使用 C/C++ 实现，性能极高。本项目目标是用纯 Java 实现类似功能，优势在于：

- **跨平台**：Java 天然跨平台，无需编译 native 代码
- **生态融合**：可无缝接入 Spring Boot、Quarkus 等 Java 生态
- **易于扩展**：Java 开发者可直接理解和修改推理逻辑
- **现代 Java**：充分利用 Java 21 的 Vector API（SIMD）、虚拟线程、Panama 等新特性

### 1.2 核心功能目标

| 功能 | 优先级 | 说明 |
|------|--------|------|
| GGUF 模型加载 | P0 | 解析 llama.cpp 标准格式 |
| LLaMA 3 推理 | P0 | 支持 1B/3B 小模型 |
| 命令行交互 | P0 | 流式输出，多轮对话 |
| Q4_0/Q8_0 量化 | P0 | 减少内存占用 |
| KV Cache | P0 | 避免重复计算 |
| Vector API 加速 | P1 | SIMD 矩阵运算 |
| Top-p / Temperature 采样 | P1 | 控制生成多样性 |
| 多模型支持 | P2 | Mistral, Qwen, Gemma |
| REST API 服务器 | P2 | OpenAI 兼容接口 |
| GPU 加速 (Panama) | P3 | 调用 OpenCL/CUDA |

### 1.3 参考项目

- [mukel/llama3.java](https://github.com/mukel/llama3.java) — 最接近本项目目标的参考实现
- [karpathy/llama2.c](https://github.com/karpathy/llama2.c) — 最清晰的 Transformer 逻辑参考
- [ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp) — GGUF 格式权威定义

---

## 2. 技术选型与依赖

### 2.1 Java 版本要求

```
Java 21+ (LTS)  ← 必须，Vector API 在此版本趋于稳定
```

### 2.2 核心 Java 特性

| 特性 | 用途 | 模块 |
|------|------|------|
| `jdk.incubator.vector` | SIMD 矩阵乘法加速 | tensor/ |
| `java.nio.MappedByteBuffer` | GGUF 文件内存映射，零拷贝 | model/ |
| `java.nio.channels.FileChannel` | 大文件高效 I/O | model/ |
| `Float.float16ToFloat()` | FP16 解量化（Java 20+） | tensor/ |
| Virtual Threads (Loom) | 并发层间计算 | inference/ |

### 2.3 Maven 依赖（最小化原则）

```xml
<dependencies>
    <!-- 命令行参数解析 -->
    <dependency>
        <groupId>info.picocli</groupId>
        <artifactId>picocli</artifactId>
        <version>4.7.5</version>
    </dependency>

    <!-- 可选：REST API 服务器模式 (P2) -->
    <dependency>
        <groupId>io.javalin</groupId>
        <artifactId>javalin</artifactId>
        <version>6.3.0</version>
        <optional>true</optional>
    </dependency>

    <!-- 测试 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

> **设计原则**：核心推理模块零外部依赖，全部使用 Java 标准库实现。

---

## 3. 整体架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                        用户层                                 │
│   CLI (交互式对话)  │  REST API Server (OpenAI 兼容)          │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                      推理协调层                               │
│   LlamaRunner   ←   ChatSession   ←   PromptBuilder          │
└──────┬───────────────────┬──────────────────────────────────┘
       │                   │
┌──────▼───────┐   ┌───────▼────────┐   ┌──────────────────┐
│  Transformer  │   │   Tokenizer    │   │     Sampler      │
│  (推理引擎)   │   │  (BPE分词器)   │   │  (采样策略)      │
└──────┬───────┘   └───────┬────────┘   └──────────────────┘
       │                   │
┌──────▼───────────────────▼──────────────────────────────────┐
│                      模型层                                   │
│   ModelWeights  │  KVCache  │  ModelConfig  │  ChatTemplate  │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                      存储/张量层                              │
│   GGUFReader   │  FloatTensor  │  Q4Tensor  │  Q8Tensor     │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 数据流

```
用户输入
   │
   ▼
Tokenizer.encode()      → [token_id, token_id, ...]
   │
   ▼
Transformer.forward()   → logits[vocab_size]   (每步)
   │
   ▼
Sampler.sample()        → next_token_id
   │
   ▼
Tokenizer.decode()      → "文字片段"
   │
   ▼
流式输出到终端
```

---

## 4. 详细模块实现计划

---

### 4.1 GGUF 文件解析器

**文件**：`src/main/java/com/llama/model/GGUFReader.java`

#### GGUF 格式结构

```
┌─────────────────────┐
│  Magic: "GGUF"      │  4 bytes
│  Version: 1/2/3     │  4 bytes (uint32)
│  Tensor Count       │  8 bytes (uint64)
│  Metadata KV Count  │  8 bytes (uint64)
├─────────────────────┤
│  Metadata KV Pairs  │  变长
│    key: string      │
│    value_type: u32  │
│    value: 变长      │
├─────────────────────┤
│  Tensor Info List   │  每条: name + dims + type + offset
├─────────────────────┤
│  [Padding to align] │  按 alignment 对齐（默认32字节）
├─────────────────────┤
│  Tensor Data        │  原始权重数据
└─────────────────────┘
```

#### 核心实现

```java
public class GGUFReader {

    // GGUF 魔数: ASCII "GGUF"
    private static final int GGUF_MAGIC = 0x46554747;

    // 元数据值类型枚举
    enum GGUFValueType {
        UINT8(0), INT8(1), UINT16(2), INT16(3),
        UINT32(4), INT32(5), FLOAT32(6), BOOL(7),
        STRING(8), ARRAY(9), UINT64(10), INT64(11), FLOAT64(12);
    }

    public static GGUFModel load(String modelPath) throws IOException {
        var path = Path.of(modelPath);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            // 使用内存映射，零拷贝读取大文件
            var buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // 1. 解析文件头
            validateMagic(buffer);
            int version = buffer.getInt();
            long tensorCount = readUInt64(buffer);
            long metaCount = readUInt64(buffer);

            // 2. 解析元数据（模型超参数）
            Map<String, Object> metadata = parseMetadata(buffer, metaCount);

            // 3. 解析 Tensor 描述信息
            List<TensorInfo> tensorInfos = parseTensorInfos(buffer, tensorCount);

            // 4. 计算数据段起始位置（需按 alignment 对齐）
            int alignment = (int) metadata.getOrDefault("general.alignment", 32L);
            long dataOffset = alignOffset(buffer.position(), alignment);

            // 5. 构建 ModelWeights（延迟加载，不立即读取权重数据）
            return new GGUFModel(metadata, tensorInfos, buffer, dataOffset);
        }
    }

    private static Map<String, Object> parseMetadata(ByteBuffer buf, long count) {
        var meta = new LinkedHashMap<String, Object>();
        for (long i = 0; i < count; i++) {
            String key = readGGUFString(buf);
            int typeId = buf.getInt();
            GGUFValueType type = GGUFValueType.values()[typeId];
            Object value = readValue(buf, type);
            meta.put(key, value);
        }
        return meta;
    }

    private static Object readValue(ByteBuffer buf, GGUFValueType type) {
        return switch (type) {
            case UINT8   -> Byte.toUnsignedInt(buf.get());
            case INT8    -> (int) buf.get();
            case UINT16  -> Short.toUnsignedInt(buf.getShort());
            case INT16   -> (int) buf.getShort();
            case UINT32  -> Integer.toUnsignedLong(buf.getInt());
            case INT32   -> buf.getInt();
            case FLOAT32 -> buf.getFloat();
            case BOOL    -> buf.get() != 0;
            case STRING  -> readGGUFString(buf);
            case ARRAY   -> readArray(buf);
            case UINT64  -> readUInt64(buf);
            case INT64   -> buf.getLong();
            case FLOAT64 -> buf.getDouble();
        };
    }

    private static String readGGUFString(ByteBuffer buf) {
        long len = readUInt64(buf);
        byte[] bytes = new byte[(int) len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
```

#### 需要从元数据提取的关键配置

| 元数据 Key | 含义 | 类型 |
|------------|------|------|
| `llama.context_length` | 最大上下文长度 | uint32 |
| `llama.embedding_length` | 隐藏层维度 dim | uint32 |
| `llama.block_count` | Transformer 层数 | uint32 |
| `llama.attention.head_count` | 注意力头数 | uint32 |
| `llama.attention.head_count_kv` | KV 头数（GQA） | uint32 |
| `llama.feed_forward_length` | FFN 中间层维度 | uint32 |
| `llama.rope.freq_base` | RoPE 频率基数 | float32 |
| `tokenizer.ggml.model` | 分词器类型（llama/bpe） | string |
| `tokenizer.ggml.tokens` | 词表 | string[] |
| `tokenizer.ggml.scores` | BPE 合并分数 | float32[] |
| `tokenizer.chat_template` | Jinja2 对话模板 | string |

---

### 4.2 量化张量系统

**文件**：`src/main/java/com/llama/tensor/`

#### 支持的量化类型

| 类型 | Bits/Weight | 内存 vs FP32 | 精度损失 | 优先支持 |
|------|-------------|-------------|---------|---------|
| F32  | 32          | 1x          | 无      | ✓ |
| F16  | 16          | 0.5x        | 极低    | ✓ |
| Q8_0 | 8          | 0.25x       | 低      | ✓ |
| Q4_0 | 4          | 0.125x      | 中      | ✓ |
| Q4_K | 4          | ~0.14x      | 低      | ✓ |
| Q6_K | 6          | ~0.19x      | 极低    | P2 |

#### 张量抽象基类

```java
public abstract class FloatTensor {
    public abstract int size();
    public abstract float get(int index);
    public abstract void set(int index, float value);

    // 点积（矩阵乘法的基础操作）
    public float dot(int thisOffset, FloatTensor other, int otherOffset, int size) {
        float sum = 0f;
        for (int i = 0; i < size; i++) {
            sum += this.get(thisOffset + i) * other.get(otherOffset + i);
        }
        return sum;
    }

    // 矩阵向量乘法: out = this(matrix) * vec
    public void matmul(FloatTensor vec, FloatTensor out, int dim0, int dim1) {
        // 可被子类覆盖以利用 SIMD
        IntStream.range(0, dim0).parallel().forEach(i ->
            out.set(i, dot(i * dim1, vec, 0, dim1))
        );
    }

    // 工厂方法：根据量化类型创建张量
    public static FloatTensor from(ByteBuffer data, GGMLType type, int size) {
        return switch (type) {
            case F32  -> new F32Tensor(data, size);
            case F16  -> new F16Tensor(data, size);
            case Q8_0 -> new Q8_0Tensor(data, size);
            case Q4_0 -> new Q4_0Tensor(data, size);
            case Q4_K -> new Q4_KTensor(data, size);
            default   -> throw new UnsupportedOperationException("Unsupported: " + type);
        };
    }
}
```

#### Q4_0 量化格式详解

```
Q4_0 Block 结构 (每 32 个 float 为一个 block):
┌──────────────────────────────────────────────────────┐
│  scale (fp16, 2 bytes)                               │
│  quants (16 bytes, 每个 byte 存 2 个 4bit 值)        │
│    byte[0]: low_nibble = quant[0], high_nibble = quant[1]
│    ...                                               │
└──────────────────────────────────────────────────────┘
Block 总大小 = 2 + 16 = 18 bytes
压缩比 = 32 * 4 bytes / 18 bytes ≈ 7.1x

解量化公式: x = scale * (quant - 8)
其中 quant ∈ [0, 15]，减8后范围 [-8, 7]
```

```java
public final class Q4_0Tensor extends FloatTensor {
    static final int BLOCK_SIZE = 32;
    static final int BLOCK_BYTES = 2 + BLOCK_SIZE / 2; // 18 bytes

    private final ByteBuffer buffer;
    private final int size;

    @Override
    public float get(int index) {
        int blockIdx = index / BLOCK_SIZE;
        int withinBlock = index % BLOCK_SIZE;
        int blockOffset = blockIdx * BLOCK_BYTES;

        // 读取 FP16 scale
        short scaleRaw = buffer.getShort(blockOffset);
        float scale = Float.float16ToFloat(scaleRaw); // Java 20+

        // 读取 4bit 量化值
        int byteIdx = blockOffset + 2 + withinBlock / 2;
        byte b = buffer.get(byteIdx);
        int quant = (withinBlock % 2 == 0)
                ? (b & 0x0F)         // 低4位
                : ((b >> 4) & 0x0F); // 高4位

        return scale * (quant - 8);
    }
}
```

#### Vector API 加速的点积（Q8_0）

```java
// 使用 Java Vector API 实现 SIMD 加速的量化矩阵乘法
public float dotVectorized(Q8_0Tensor other, int otherOffset, int size) {
    var SPECIES = FloatVector.SPECIES_PREFERRED; // 自动选最宽 SIMD 宽度
    var sum = FloatVector.zero(SPECIES);

    // 每次处理一个 block
    for (int blockStart = 0; blockStart < size; blockStart += BLOCK_SIZE) {
        float scaleA = this.getScale(blockStart / BLOCK_SIZE);
        float scaleB = other.getScale((otherOffset + blockStart) / BLOCK_SIZE);
        float combinedScale = scaleA * scaleB;

        // SIMD 批量计算整数点积
        for (int i = 0; i < BLOCK_SIZE; i += SPECIES.length()) {
            var va = IntVector.fromArray(INT_SPECIES, this.quants, blockStart + i)
                              .convert(VectorOperators.I2F, 0).reinterpretAsFloats();
            var vb = IntVector.fromArray(INT_SPECIES, other.quants, otherOffset + blockStart + i)
                              .convert(VectorOperators.I2F, 0).reinterpretAsFloats();
            sum = va.fma(vb, sum);
        }
    }
    return sum.reduceLanes(VectorOperators.ADD);
}
```

---

### 4.3 BPE 分词器

**文件**：`src/main/java/com/llama/model/Tokenizer.java`

#### BPE 算法流程

```
输入: "Hello World"

1. 预分词（按空格/Unicode类别分割）:
   ["Hello", "▁World"]  ← SentencePiece 用 ▁ 表示词首空格

2. 字节级 fallback（处理 UTF-8）:
   每个字符映射到 byte-level token

3. BPE 合并循环:
   while 存在可合并的相邻 pair:
       找到 scores 最高的 pair (t1, t2)
       将所有 t1+t2 替换为合并后的 token
   直到无法继续合并

4. 输出 token id 序列:
   [9906, 3304]  ← "Hello", " World" 对应的 id
```

#### 实现

```java
public class Tokenizer {
    private final String[] vocab;          // id → token 字符串
    private final float[] scores;          // BPE 合并优先级分数
    private final Map<String, Integer> tokenIndex; // token 字符串 → id
    private final int bosToken;
    private final int eosToken;
    private final boolean byteLevel;       // 是否使用 byte-level BPE

    public int[] encode(String text, boolean addBos, boolean addEos) {
        var tokens = new ArrayList<Integer>();
        if (addBos) tokens.add(bosToken);

        // 1. 预处理：添加 SentencePiece 前缀空格
        if (!text.isEmpty()) {
            String normalized = " " + text;

            // 2. 逐字符初始化（byte-level fallback）
            for (int i = 0; i < normalized.length(); ) {
                int cp = normalized.codePointAt(i);
                String piece = encodeCodepoint(cp);
                Integer id = tokenIndex.get(piece);
                if (id != null) {
                    tokens.add(id);
                } else {
                    // UTF-8 byte fallback
                    for (byte b : piece.getBytes(StandardCharsets.UTF_8)) {
                        tokens.add(bytePieceToToken(b));
                    }
                }
                i += Character.charCount(cp);
            }
        }

        // 3. BPE 合并循环
        while (true) {
            float bestScore = Float.NEGATIVE_INFINITY;
            int bestId = -1, mergePos = -1;

            for (int i = 0; i < tokens.size() - 1; i++) {
                String merged = vocab[tokens.get(i)] + vocab[tokens.get(i + 1)];
                Integer id = tokenIndex.get(merged);
                if (id != null && scores[id] > bestScore) {
                    bestScore = scores[id];
                    bestId = id;
                    mergePos = i;
                }
            }

            if (mergePos == -1) break; // 无更多合并

            tokens.set(mergePos, bestId);
            tokens.remove(mergePos + 1);
        }

        if (addEos) tokens.add(eosToken);
        return tokens.stream().mapToInt(Integer::intValue).toArray();
    }

    public String decode(int prevToken, int token) {
        String piece = vocab[token];
        // 特殊处理：句首的前缀空格
        if (prevToken == bosToken && piece.startsWith(" ")) {
            piece = piece.substring(1);
        }
        // 处理 byte token: "<0x0A>" → '\n'
        return decodeBytePiece(piece);
    }
}
```

---

### 4.4 Transformer 推理引擎

**文件**：`src/main/java/com/llama/inference/Transformer.java`

#### LLaMA 3 架构参数（1B 模型示例）

| 参数 | 值 |
|------|----|
| `dim` (hidden size) | 2048 |
| `n_layers` | 16 |
| `n_heads` | 32 |
| `n_kv_heads` | 8 (GQA) |
| `head_size` | dim / n_heads = 64 |
| `ffn_dim` | 8192 |
| `vocab_size` | 128256 |
| `max_seq_len` | 131072 |
| `rope_theta` | 500000.0 |

#### 前向传播完整流程

```
输入: token_id (int), position (int)

Step 1: Token Embedding
  x = embedding_table[token_id]   → shape: [dim]

Step 2: 逐层 Transformer Block
  for layer in range(n_layers):

    Step 2a: Pre-Attention RMSNorm
      xb = RMSNorm(x, rms_att_weight[layer])

    Step 2b: QKV 线性投影
      q = xb @ wq[layer]   → [n_heads * head_size]
      k = xb @ wk[layer]   → [n_kv_heads * head_size]
      v = xb @ wv[layer]   → [n_kv_heads * head_size]

    Step 2c: RoPE 旋转位置编码（原地修改 q, k）
      apply_rope(q, k, position)

    Step 2d: 写入 KV Cache
      kv_cache[layer].k[position] = k
      kv_cache[layer].v[position] = v

    Step 2e: Grouped Query Attention (GQA)
      for each head h:
        scores = q[h] @ k_cache[0..pos]ᵀ / sqrt(head_size)
        scores = softmax(scores)
        x_att[h] = scores @ v_cache[0..pos]

    Step 2f: 输出投影 + 残差连接
      x = x + (x_att @ wo[layer])

    Step 2g: Pre-FFN RMSNorm
      xb = RMSNorm(x, rms_ffn_weight[layer])

    Step 2h: SwiGLU FFN
      gate = SiLU(xb @ w1[layer])
      up   = xb @ w3[layer]
      x = x + (gate * up) @ w2[layer]

Step 3: Final RMSNorm
  x = RMSNorm(x, rms_final_weight)

Step 4: 输出投影（lm_head）
  logits = x @ output_weight   → [vocab_size]

输出: logits
```

#### RMSNorm 实现

```java
// RMSNorm: x_norm = x / rms(x) * weight
// rms(x) = sqrt(mean(x²) + ε)
private FloatTensor rmsnorm(FloatTensor x, FloatTensor weight) {
    // 计算均方根
    float ss = 0f;
    for (int i = 0; i < config.dim; i++) {
        float v = x.get(i);
        ss += v * v;
    }
    ss = 1f / (float) Math.sqrt(ss / config.dim + 1e-5f);

    // 归一化并缩放
    var out = new ArrayFloatTensor(config.dim);
    for (int i = 0; i < config.dim; i++) {
        out.set(i, weight.get(i) * ss * x.get(i));
    }
    return out;
}
```

#### RoPE 旋转位置编码

```java
// 对 q 和 k 应用旋转位置编码
// 公式: [q0, q1] → [q0*cos - q1*sin, q0*sin + q1*cos]
private void applyRoPE(FloatTensor q, FloatTensor k, int pos) {
    int headSize = config.dim / config.nHeads;

    for (int h = 0; h < config.nHeads; h++) {
        for (int i = 0; i < headSize; i += 2) {
            // 旋转频率: freq = 1 / (rope_theta ^ (2i / head_size))
            double freq = 1.0 / Math.pow(config.ropeTheta, (double) i / headSize);
            double angle = pos * freq;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            int offset = h * headSize + i;
            float q0 = q.get(offset), q1 = q.get(offset + 1);
            q.set(offset,     q0 * cos - q1 * sin);
            q.set(offset + 1, q0 * sin + q1 * cos);

            // 注意：KV heads 数量可能不同（GQA）
            if (h < config.nKVHeads) {
                int kOffset = h * headSize + i;
                float k0 = k.get(kOffset), k1 = k.get(kOffset + 1);
                k.set(kOffset,     k0 * cos - k1 * sin);
                k.set(kOffset + 1, k0 * sin + k1 * cos);
            }
        }
    }
}
```

#### Grouped Query Attention (GQA)

```java
// LLaMA 3 使用 GQA：多个 Q head 共享同一对 K/V head
// n_heads = 32, n_kv_heads = 8 → 每 4 个 Q head 共享 1 个 KV head
private void attention(FloatTensor q, int pos, int layer) {
    int headSize = config.dim / config.nHeads;
    int kvHeadRatio = config.nHeads / config.nKVHeads; // = 4

    for (int h = 0; h < config.nHeads; h++) {
        int kvHead = h / kvHeadRatio; // 对应的 KV head 索引

        // 计算注意力分数: q[h] · k_cache[0..pos]
        float[] scores = new float[pos + 1];
        float scale = (float) (1.0 / Math.sqrt(headSize));

        for (int t = 0; t <= pos; t++) {
            float score = 0f;
            for (int i = 0; i < headSize; i++) {
                score += q.get(h * headSize + i)
                       * kvCache.getKey(layer, t, kvHead * headSize + i);
            }
            scores[t] = score * scale;
        }

        // Softmax
        softmax(scores, pos + 1);

        // 加权求和 V
        for (int t = 0; t <= pos; t++) {
            for (int i = 0; i < headSize; i++) {
                state.xb.add(h * headSize + i,
                    scores[t] * kvCache.getValue(layer, t, kvHead * headSize + i));
            }
        }
    }
}
```

---

### 4.5 KV Cache

**文件**：`src/main/java/com/llama/inference/KVCache.java`

KV Cache 是推理加速的关键：缓存历史 token 的 Key/Value，避免重复计算。

```java
public class KVCache {
    // 布局: [layer][seq_pos][kv_head * head_size]
    private final float[][] keyCache;    // [n_layers * max_seq_len * n_kv_heads * head_size]
    private final float[][] valueCache;

    public KVCache(ModelConfig config) {
        int kvDim = config.nKVHeads * config.headSize;
        this.keyCache   = new float[config.nLayers][config.maxSeqLen * kvDim];
        this.valueCache = new float[config.nLayers][config.maxSeqLen * kvDim];
    }

    public void storeKey(int layer, int pos, FloatTensor k, int kvDim) {
        k.copyTo(keyCache[layer], pos * kvDim, kvDim);
    }

    public float getKey(int layer, int pos, int i) {
        return keyCache[layer][pos * kvDimension + i];
    }

    // 内存估算: 1B 模型 KV Cache
    // n_layers=16, max_seq_len=4096, n_kv_heads=8, head_size=64
    // = 16 * 4096 * 8 * 64 * 4 bytes * 2 (K+V) ≈ 256 MB
}
```

---

### 4.6 采样器

**文件**：`src/main/java/com/llama/inference/Sampler.java`

#### 采样策略对比

| 策略 | 参数 | 效果 |
|------|------|------|
| Greedy | - | 总选最高概率，确定性但重复 |
| Temperature | `temp > 0` | 调整分布尖锐度 |
| Top-K | `k=40` | 只从前K个选 |
| Top-P (Nucleus) | `p=0.9` | 从累积概率≥p的最小集合选 |

```java
public class Sampler {
    private final float temperature;
    private final float topP;
    private final long seed;
    private final Random random;

    public int sample(FloatTensor logits) {
        int vocabSize = logits.size();

        if (temperature == 0f) {
            // Greedy decoding
            return argmax(logits, vocabSize);
        }

        // 1. 应用 Temperature
        float[] probs = new float[vocabSize];
        for (int i = 0; i < vocabSize; i++) {
            probs[i] = logits.get(i) / temperature;
        }

        // 2. Softmax
        softmax(probs);

        // 3. Top-P Nucleus Sampling
        if (topP < 1.0f) {
            return sampleTopP(probs, topP);
        }

        // 4. 按概率采样
        return sampleMultinomial(probs);
    }

    private int sampleTopP(float[] probs, float p) {
        // 按概率降序排序
        Integer[] indices = IntStream.range(0, probs.length)
            .boxed()
            .sorted((a, b) -> Float.compare(probs[b], probs[a]))
            .toArray(Integer[]::new);

        // 找到累积概率恰好超过 p 的截止点
        float cumProb = 0f;
        int lastIdx = 0;
        for (int i = 0; i < indices.length; i++) {
            cumProb += probs[indices[i]];
            if (cumProb >= p) { lastIdx = i; break; }
        }

        // 在截止范围内重新归一化并采样
        float r = random.nextFloat() * cumProb;
        float cdf = 0f;
        for (int i = 0; i <= lastIdx; i++) {
            cdf += probs[indices[i]];
            if (r < cdf) return indices[i];
        }
        return indices[lastIdx];
    }
}
```

---

### 4.7 Chat Template 处理器

**文件**：`src/main/java/com/llama/model/ChatTemplate.java`

不同模型有不同的对话格式，需从 GGUF 元数据中读取 `tokenizer.chat_template`。

#### LLaMA 3 Instruct 格式

```
<|begin_of_text|>
<|start_header_id|>system<|end_header_id|>
You are a helpful assistant.<|eot_id|>
<|start_header_id|>user<|end_header_id|>
{用户输入}<|eot_id|>
<|start_header_id|>assistant<|end_header_id|>
```

#### Qwen 2.5 格式

```
<|im_start|>system
You are a helpful assistant.<|im_end|>
<|im_start|>user
{用户输入}<|im_end|>
<|im_start|>assistant
```

```java
public class ChatTemplate {
    // 内置常见模型的模板（作为 GGUF 解析失败时的 fallback）
    public enum TemplateType { LLAMA3, QWEN, MISTRAL, CHATML, GEMMA }

    public String buildPrompt(List<Message> history, ModelConfig config) {
        return switch (detectTemplate(config)) {
            case LLAMA3 -> buildLlama3Prompt(history, config.systemPrompt);
            case CHATML -> buildChatMLPrompt(history, config.systemPrompt);
            case MISTRAL -> buildMistralPrompt(history);
            default -> buildGenericPrompt(history);
        };
    }

    private String buildLlama3Prompt(List<Message> history, String system) {
        var sb = new StringBuilder("<|begin_of_text|>");
        if (system != null && !system.isEmpty()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
              .append(system)
              .append("<|eot_id|>");
        }
        for (var msg : history) {
            sb.append("<|start_header_id|>").append(msg.role()).append("<|end_header_id|>\n\n")
              .append(msg.content())
              .append("<|eot_id|>");
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
        return sb.toString();
    }
}
```

---

### 4.8 CLI 交互界面

**文件**：`src/main/java/com/llama/Main.java`

```java
@Command(name = "llama-java", mixinStandardHelpOptions = true,
         description = "Run local LLM inference in pure Java")
public class Main implements Runnable {

    @Option(names = {"-m", "--model"}, required = true, description = "GGUF model path")
    String modelPath;

    @Option(names = {"-t", "--temperature"}, defaultValue = "0.7")
    float temperature;

    @Option(names = {"-p", "--top-p"}, defaultValue = "0.9")
    float topP;

    @Option(names = {"--max-tokens"}, defaultValue = "512")
    int maxTokens;

    @Option(names = {"-s", "--system"}, defaultValue = "You are a helpful assistant.")
    String systemPrompt;

    @Option(names = {"--seed"}, defaultValue = "-1")
    long seed;

    @Override
    public void run() {
        try {
            // 加载模型
            System.err.println("Loading model: " + modelPath);
            long t0 = System.nanoTime();
            var model = GGUFReader.load(modelPath);
            var transformer = new Transformer(model);
            var tokenizer = new Tokenizer(model);
            var sampler = new Sampler(temperature, topP, seed);
            var template = new ChatTemplate();
            long loadMs = (System.nanoTime() - t0) / 1_000_000;
            System.err.printf("Model loaded in %d ms%n", loadMs);
            System.err.printf("Parameters: dim=%d, layers=%d, heads=%d, vocab=%d%n",
                model.config.dim, model.config.nLayers,
                model.config.nHeads, model.config.vocabSize);
            System.err.println("Type your message. Use /clear to reset, /quit to exit.\n");

            // 交互循环
            var chatHistory = new ArrayList<Message>();
            var scanner = new Scanner(System.in);

            while (true) {
                System.out.print("You: ");
                System.out.flush();
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) continue;
                if (input.equals("/quit") || input.equals("/exit")) break;
                if (input.equals("/clear")) {
                    chatHistory.clear();
                    transformer.resetCache();
                    System.out.println("[Context cleared]");
                    continue;
                }

                chatHistory.add(new Message("user", input));
                String prompt = template.buildPrompt(chatHistory, model.config);
                int[] tokens = tokenizer.encode(prompt, true, false);

                System.out.print("AI: ");
                System.out.flush();

                // 流式生成
                var responseBuilder = new StringBuilder();
                long genStart = System.nanoTime();
                int genTokens = 0;

                for (int pos = 0; pos < tokens.length + maxTokens; pos++) {
                    int inputToken = pos < tokens.length
                        ? tokens[pos]
                        : tokenizer.encode(responseBuilder.toString(), false, false)[genTokens - 1];

                    var logits = transformer.forward(inputToken, pos);
                    if (pos < tokens.length - 1) continue; // Prefill 阶段

                    int nextToken = sampler.sample(logits);
                    if (nextToken == tokenizer.eosToken()) break;

                    String piece = tokenizer.decode(nextToken);
                    System.out.print(piece);
                    System.out.flush();
                    responseBuilder.append(piece);
                    genTokens++;
                }

                long genMs = (System.nanoTime() - genStart) / 1_000_000;
                double tokPerSec = genTokens * 1000.0 / genMs;
                System.out.printf("%n[%d tokens, %.1f tok/s]%n%n", genTokens, tokPerSec);

                chatHistory.add(new Message("assistant", responseBuilder.toString()));
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}
```

---

## 5. 性能优化策略

### 5.1 内存优化

| 技术 | 效果 | 实现 |
|------|------|------|
| 内存映射文件 | 避免将 GB 级模型全部载入堆内存 | `FileChannel.map()` |
| 量化（Q4_0） | 模型内存降低 8x | 延迟解量化 |
| 直接内存（Direct Buffer） | 减少 GC 压力 | `ByteBuffer.allocateDirect()` |
| KV Cache 预分配 | 避免运行时 GC | 启动时一次性分配 |

### 5.2 计算优化

```
优化层次（由低到高）:

Level 1: 纯 Java 基础实现（先跑通）
  └─ 串行 for 循环矩阵乘法

Level 2: Java 并行流（简单加速）
  └─ IntStream.range().parallel() 多核并行

Level 3: Vector API SIMD（最大化 CPU 利用率）
  └─ FloatVector/IntVector SIMD 指令
  └─ 自动选择 AVX-512 / AVX2 / SSE4.2

Level 4: 缓存友好的内存布局
  └─ 行优先 vs 列优先权重排列优化
  └─ 预计算 RoPE 频率表
```

### 5.3 预期性能基准（MacBook Pro M3，Q4_0）

| 模型 | 内存占用 | Prefill 速度 | Generate 速度 |
|------|---------|------------|-------------|
| LLaMA 3.2 1B Q4_0 | ~800 MB | ~200 tok/s | ~30 tok/s |
| LLaMA 3.2 3B Q4_0 | ~2.0 GB | ~80 tok/s | ~15 tok/s |
| Qwen 2.5 1.5B Q4_0 | ~1.0 GB | ~160 tok/s | ~25 tok/s |

> 注：纯 Java 性能约为 llama.cpp 的 40-60%，对话体验可接受。

---

## 6. 阶段性开发计划

### Phase 1：核心功能（第 1-2 周）

**目标**：能跑通 LLaMA 3.2 1B，实现基本对话

- [ ] 项目骨架搭建（Maven 多模块）
- [ ] GGUF 文件解析（F32/F16 格式）
- [ ] FloatTensor 基础实现
- [ ] BPE Tokenizer
- [ ] Transformer 前向传播（串行版）
- [ ] Greedy 采样
- [ ] 基础 CLI（单轮问答）
- [ ] 单元测试：Tokenizer 正确性、矩阵运算精度

**验收标准**：
```
$ java -jar llama-java.jar -m Llama-3.2-1B-Instruct.gguf
You: 你好
AI: 你好！我是一个AI助手...
```

---

### Phase 2：量化与性能（第 3 周）

**目标**：支持 Q4_0/Q8_0，性能提升 3x

- [ ] Q8_0 量化张量实现
- [ ] Q4_0 量化张量实现
- [ ] Q4_K_M 量化支持
- [ ] 并行流矩阵乘法
- [ ] Vector API SIMD 矩阵乘法
- [ ] 性能基准测试（tokens/sec）
- [ ] 内存映射优化

**验收标准**：
- Q4_0 模型加载成功
- 生成速度 ≥ 15 tok/s（1B 模型）

---

### Phase 3：对话体验（第 4 周）

**目标**：完整对话体验，媲美 ollama CLI

- [ ] 多轮对话历史管理
- [ ] Chat Template 解析（LLaMA3/ChatML/Mistral）
- [ ] Temperature + Top-P 采样
- [ ] 流式输出（字符逐个打印）
- [ ] 性能统计显示（tok/s）
- [ ] `/clear` `/help` `/quit` 命令
- [ ] Qwen 2.5 模型验证
- [ ] Mistral 模型验证

---

### Phase 4：扩展功能（第 5-6 周，可选）

**目标**：打磨产品体验

- [ ] REST API 服务器（OpenAI /v1/chat/completions 兼容）
- [ ] 配置文件支持（`~/.llama-java/config.yaml`）
- [ ] 模型管理命令（list/download/remove）
- [ ] 系统提示词文件支持
- [ ] 日志与调试模式
- [ ] Gemma 2 模型支持
- [ ] GraalVM Native Image 打包（减少启动时间）

---

## 7. 测试与验证方案

### 7.1 单元测试

```java
// TokenizerTest.java
@Test
void testEncodeDecodeRoundtrip() {
    var tokenizer = loadTokenizer("testdata/llama3-tokenizer.json");
    String text = "Hello, 世界！ This is a test.";
    int[] tokens = tokenizer.encode(text, false, false);
    String decoded = tokenizer.decodeAll(tokens);
    assertEquals(text, decoded);
}

// TensorTest.java
@Test
void testQ4_0Dequantize() {
    // 构造已知 Q4_0 block，验证解量化精度
    float scale = 0.5f;
    byte[] quants = {0x78}; // low=8, high=7 → dequant: 0*0.5=0, -1*0.5=-0.5
    var tensor = new Q4_0Tensor(wrap(scale, quants), 2);
    assertEquals(0.0f, tensor.get(0), 1e-3f);
    assertEquals(-0.5f, tensor.get(1), 1e-3f);
}
```

### 7.2 集成测试（输出质量验证）

```java
@Test
void testSimpleGreeting() {
    var runner = LlamaRunner.load("models/Llama-3.2-1B-Q4_0.gguf");
    String response = runner.chat("Say exactly: Hello World");
    assertTrue(response.contains("Hello World"),
        "Expected greeting in: " + response);
}

@Test
void testMathReasoning() {
    var runner = LlamaRunner.load("models/Llama-3.2-1B-Q4_0.gguf");
    String response = runner.chat("What is 7 * 8? Answer with just the number.");
    assertTrue(response.contains("56"),
        "Expected 56 in: " + response);
}
```

### 7.3 性能基准测试

```bash
# 运行基准测试
java -jar llama-java.jar benchmark \
  --model models/Llama-3.2-1B-Q4_0.gguf \
  --runs 10 \
  --prompt "Explain the Transformer architecture in detail."

# 预期输出:
# Prefill: 512 tokens in 2.1s = 243 tok/s
# Generate: 256 tokens in 8.4s = 30 tok/s
# Memory: 847 MB
```

---

## 8. 支持的模型列表

### 推荐入门模型（资源要求低）

| 模型 | 量化 | 内存 | 下载地址 |
|------|------|------|---------|
| LLaMA 3.2 1B Instruct | Q4_0 | ~800 MB | HuggingFace |
| LLaMA 3.2 3B Instruct | Q4_0 | ~2 GB | HuggingFace |
| Qwen 2.5 1.5B Instruct | Q4_0 | ~1 GB | HuggingFace |
| Gemma 2 2B Instruct | Q4_0 | ~1.5 GB | HuggingFace |
| Mistral 7B v0.3 | Q4_0 | ~4 GB | HuggingFace |

### 获取 GGUF 模型

```bash
# 使用 huggingface-cli 下载
pip install huggingface_hub
huggingface-cli download \
  bartowski/Llama-3.2-1B-Instruct-GGUF \
  Llama-3.2-1B-Instruct-Q4_0.gguf \
  --local-dir ./models

# 或直接用浏览器访问 https://huggingface.co
# 搜索: "Llama-3.2-1B-Instruct-GGUF"
```

---

## 9. 项目结构

```
llama-java/
├── pom.xml                              # Maven 父模块
├── README.md
├── models/                              # 放置 .gguf 模型文件（git忽略）
│   └── .gitkeep
│
├── core/                                # 核心库模块（无外部依赖）
│   └── src/main/java/com/llama/
│       ├── model/
│       │   ├── GGUFReader.java          # GGUF 格式解析
│       │   ├── GGUFModel.java           # 模型元数据 + 权重索引
│       │   ├── ModelConfig.java         # 超参数配置
│       │   ├── ModelWeights.java        # 权重访问接口
│       │   ├── Tokenizer.java           # BPE 分词器
│       │   └── ChatTemplate.java        # 对话模板处理
│       │
│       ├── tensor/
│       │   ├── FloatTensor.java         # 张量抽象基类
│       │   ├── ArrayFloatTensor.java    # 堆内存 FP32 张量
│       │   ├── F16Tensor.java           # FP16 张量
│       │   ├── Q8_0Tensor.java          # Q8_0 量化张量
│       │   ├── Q4_0Tensor.java          # Q4_0 量化张量
│       │   └── Q4_KTensor.java          # Q4_K 量化张量
│       │
│       ├── inference/
│       │   ├── Transformer.java         # 推理引擎主类
│       │   ├── Attention.java           # 注意力机制（含GQA）
│       │   ├── FFN.java                 # SwiGLU FFN
│       │   ├── KVCache.java             # KV Cache
│       │   ├── Sampler.java             # 采样策略
│       │   └── RunState.java            # 推理中间状态缓存
│       │
│       └── LlamaRunner.java             # 高层 API 入口
│
├── cli/                                 # 命令行应用模块
│   └── src/main/java/com/llama/cli/
│       ├── Main.java                    # CLI 入口（picocli）
│       └── BenchmarkCommand.java        # 性能测试命令
│
└── server/                              # REST API 服务器（可选，P2）
    └── src/main/java/com/llama/server/
        ├── ApiServer.java               # Javalin HTTP 服务器
        └── OpenAIHandler.java           # OpenAI 兼容 API
```

---

## 10. 构建与运行

### 构建

```bash
# 克隆后首次构建
mvn clean package -DskipTests

# 含 Vector API 支持的构建
mvn clean package -DskipTests \
  -Dmaven.compiler.compilerArgs="--add-modules=jdk.incubator.vector"
```

### 运行（基础）

```bash
# 交互对话
java --add-modules jdk.incubator.vector \
     -Xmx4g \
     -jar cli/target/llama-java-cli.jar \
     --model models/Llama-3.2-1B-Instruct-Q4_0.gguf

# 自定义参数
java --add-modules jdk.incubator.vector \
     -jar cli/target/llama-java-cli.jar \
     --model models/Qwen2.5-1.5B-Instruct-Q4_0.gguf \
     --temperature 0.8 \
     --top-p 0.95 \
     --max-tokens 1024 \
     --system "You are a helpful coding assistant."

# 性能基准
java --add-modules jdk.incubator.vector \
     -jar cli/target/llama-java-cli.jar \
     --model models/Llama-3.2-1B-Q4_0.gguf \
     benchmark --runs 5
```

### JVM 调优参数

```bash
# 生产建议参数
java \
  --add-modules jdk.incubator.vector \
  -server \
  -XX:+UseZGC \                    # 低延迟 GC（Java 21 稳定）
  -Xmx6g \                         # 根据模型大小调整
  -XX:+AlwaysPreTouch \            # 预分配内存，减少运行时 GC
  -XX:+UseTransparentHugePages \   # Linux 大页优化
  -jar llama-java-cli.jar ...
```

---

## 11. 里程碑与交付物

| 里程碑 | 时间 | 交付物 | 验收标准 |
|--------|------|--------|---------|
| M1: Hello World | 第1周末 | 能加载 F32 模型并生成文本 | LLaMA 3.2 1B 可运行 |
| M2: 量化支持 | 第2周末 | Q4_0/Q8_0 张量正确解量化 | 精度误差 < 1% |
| M3: 完整对话 | 第3周末 | 流式多轮对话、Chat Template | 主观体验流畅 |
| M4: 性能优化 | 第4周末 | Vector API 加速 | ≥15 tok/s (1B Q4_0) |
| M5: 多模型 | 第5周末 | Qwen/Mistral/Gemma 支持 | 3个模型全部通过测试 |
| M6: REST API | 第6周末 | OpenAI 兼容 HTTP 服务 | curl 调用成功 |

---

## 附录：关键参考资料

| 资源 | 链接 |
|------|------|
| GGUF 格式规范 | https://github.com/ggerganov/ggml/blob/master/docs/gguf.md |
| llama3.java 参考实现 | https://github.com/mukel/llama3.java |
| Java Vector API 文档 | https://openjdk.org/jeps/469 |
| LLaMA 3 论文 | https://arxiv.org/abs/2407.21783 |
| Transformer 原理 | https://arxiv.org/abs/1706.03762 |
| BPE 分词器 | https://huggingface.co/learn/nlp-course/chapter6/5 |

---

*文档版本: v1.0 | 最后更新: 2026-05*
