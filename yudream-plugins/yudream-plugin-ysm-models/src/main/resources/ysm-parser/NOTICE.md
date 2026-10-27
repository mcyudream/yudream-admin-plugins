# YSM parser provenance

- Parser project: https://github.com/OpenYSM/YSMParser (MIT; parser-LICENSE).
- WASI memory-output adaptation: https://github.com/eghrhegpe/ysm-model-manager
- Pinned adaptation commit: d74f850ff3f1ca30af20195ecb77a5f7785f770e (Apache-2.0; manager-LICENSE and manager-NOTICE).
- Binary source: https://github.com/eghrhegpe/ysm-model-manager/blob/d74f850ff3f1ca30af20195ecb77a5f7785f770e/go/ysmwasi/YSMParser-mem-noeh.wasm
- SHA-256: 38e112ebde097cb626c8db3d89716351a52fc57924cb647434c34f4aba106345
- Upstream build notes are copied verbatim in BUILD-UPSTREAM.md. The adapted C++ source is not fully present in that repository; the binary is vendored as supplied, not represented as a reproducible local source build.
- The binary is run with Chicory 1.7.5 (Apache-2.0) and its ASM dependencies, on the website only. No JavaScript/native executable installation is needed. It is not bundled into the Folia/Fabric runtime.
- Each decode has independent memory capped at 256 MiB, a 30 second time budget, 16 MiB input, 32 MiB output, 512 files and a single active worker. Only the fixed parser is executed; uploaded model content is never loaded as executable code. Guest imports expose no filesystem or network.
- Models and textures are supplied by the uploader and retain their original licensing.
