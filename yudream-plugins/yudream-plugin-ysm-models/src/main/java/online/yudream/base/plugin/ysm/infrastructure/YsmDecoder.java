package online.yudream.base.plugin.ysm.infrastructure;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasm.*;
import com.dylibso.chicory.wasm.types.*;
import online.yudream.ysm.model.ModelCompiler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Fixed, vendored YSM parser. Guest has bounded private memory and no filesystem/network access. */
public final class YsmDecoder implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("ysm-decode").factory());
    private final Semaphore available = new Semaphore(1);
    private WasmModule module;
    private Function<Instance, Machine> machine;
    private static final int MAX_OUTPUT = 32 * 1024 * 1024;

    public Map<String, byte[]> decode(byte[] source) {
        if (source.length == 0 || source.length > ModelCompiler.MAX_UPLOAD) throw new IllegalArgumentException("模型文件需要在 1 B–16 MiB 之间");
        if (!available.tryAcquire()) throw new IllegalArgumentException("正在解析另一个模型，请稍后重试");
        Future<Map<String, byte[]>> task;
        try { task = worker.submit(() -> { try { return decodeNow(source); } finally { available.release(); } }); }
        catch (RejectedExecutionException e) { available.release(); throw new IllegalStateException("模型解析器已停止", e); }
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException e) { task.cancel(true); throw new IllegalArgumentException("YSM 解析超过 30 秒，请检查文件或使用明文 ZIP", e); }
        catch (InterruptedException e) { task.cancel(true); Thread.currentThread().interrupt(); throw new IllegalStateException("YSM 解析已中断", e); }
        catch (ExecutionException e) { throw new IllegalArgumentException("无法解析此 YSM：文件损坏、格式不支持或解码内容超限", e.getCause()); }
    }
    private Map<String, byte[]> decodeNow(byte[] source) throws Exception {
        if (module == null) {
            try (var in = YsmDecoder.class.getResourceAsStream("/ysm-parser/parser.wasm")) {
                if (in == null) throw new IllegalStateException("缺少 YSM 解析器资源");
                var parsed = Parser.parse(in);
                var compiled = MachineFactoryCompiler.compile(parsed);
                module = parsed; machine = compiled;
            }
        }
        var imports = ImportValues.builder();
        for (var value : module.importSection().stream().toList()) {
            var f = (FunctionImport) value;
            if (!Set.of("wasi_snapshot_preview1", "env").contains(f.module())) throw new IllegalStateException("Unexpected parser import");
            var type = module.typeSection().getType(f.typeIndex());
            imports.addFunction(new HostFunction(f.module(), f.name(), type, (instance, a) -> {
                var m = instance.memory(); long result = 0;
                switch (f.module() + ":" + f.name()) {
                    case "wasi_snapshot_preview1:args_sizes_get", "wasi_snapshot_preview1:environ_sizes_get" -> { m.writeI32((int)a[0], 0); m.writeI32((int)a[1], 0); }
                    case "wasi_snapshot_preview1:args_get", "wasi_snapshot_preview1:environ_get", "env:emscripten_notify_memory_growth", "env:__syscall_getdents64" -> { }
                    case "wasi_snapshot_preview1:proc_exit" -> throw new IllegalArgumentException("Parser exited");
                    case "wasi_snapshot_preview1:clock_time_get" -> m.writeLong((int)a[2], System.nanoTime());
                    case "wasi_snapshot_preview1:fd_write" -> {
                        // Discard parser diagnostics; never forward model data to application logs.
                        if (a[2] < 0 || a[2] > 1024) throw new IllegalArgumentException("Too many IO vectors");
                        long total = 0; for (int n = 0; n < a[2]; n++) total += m.readU32((int)a[1] + n*8 + 4);
                        m.writeI32((int)a[3], (int)Math.min(total, Integer.MAX_VALUE));
                    }
                    case "wasi_snapshot_preview1:fd_read" -> m.writeI32((int)a[3], 0);
                    case "wasi_snapshot_preview1:fd_close", "wasi_snapshot_preview1:fd_seek" -> result = 8; // EBADF, no descriptors exposed
                    case "env:__syscall_getcwd" -> { if (a[1] < 8) result = -34; else { m.writeCString((int)a[0], "/output"); result = 7; } }
                    case "env:__syscall_readlinkat" -> result = -22;
                    default -> throw new IllegalStateException("Unexpected parser function: " + f.name());
                }
                return type.returns().isEmpty() ? new long[0] : new long[]{result};
            }));
        }
        var instance = Instance.builder(module).withStart(false).withMachineFactory(machine)
            .withMemoryFactory(limits -> new ByteArrayMemory(new MemoryLimits(limits.initialPages(), 4096)))
            .withImportValues(imports.build()).build();
        instance.exports().function("__wasm_call_ctors").apply();
        var mem = instance.memory();
        int input = (int)instance.exports().function("malloc").apply(source.length)[0];
        int slot = (int)instance.exports().function("malloc").apply(4)[0];
        if (input == 0 || slot == 0) throw new IllegalArgumentException("Parser allocation failed");
        mem.write(input, source); mem.writeI32(slot, 0);
        instance.exports().function("ysm_decode_to_memory").apply(input, source.length, slot);
        int cursor = mem.readInt(slot); if (cursor <= 0) throw new IllegalArgumentException("Empty parser output");
        int count = mem.readInt(cursor); cursor += 4;
        if (count < 1 || count > 512) throw new IllegalArgumentException("模型文件数量需要在 1–512 之间");
        var files = new TreeMap<String, byte[]>(); long total = 0;
        for (int n = 0; n < count; n++) {
            int nameLength = mem.readInt(cursor), dataLength = mem.readInt(cursor + 4); cursor += 8;
            if (nameLength < 1 || nameLength > 1024 || dataLength < 0 || (total += dataLength) > MAX_OUTPUT) throw new IllegalArgumentException("解码内容超过限制");
            String path = new String(mem.readBytes(cursor, nameLength), StandardCharsets.UTF_8); cursor += nameLength;
            ModelCompiler.safePath(path);
            if (files.put(path, mem.readBytes(cursor, dataLength)) != null) throw new IllegalArgumentException("模型存在重复路径");
            cursor += dataLength;
        }
        return files;
    }
    @Override public void close() { worker.shutdownNow(); }
}
