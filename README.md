> **Android app upgrades:** A different signing key on each GitHub Actions runner caused the old APKs to require uninstall/reinstall. This project now pins one permanent signing certificate. After the one-time signing-secret setup and one final migration from the old temporary-signed APK, every later permanently signed APK upgrades in place, retaining the original app's private manuals, indexes and model settings. The signing private key is encrypted in git; its passphrase must be kept as a GitHub Actions secret.

# ShipRAG Android — source-first offline library

**Status:** Offline-first Android app, compiled and tested using GitHub Actions. For installable update builds, configure the permanent signing secret once. See [signing/README.md](signing/README.md).

Designed against the current `snehithgit/docling-stage2b` retrieval artifacts, not as a port of its conversion/review pipeline. It leaves the original Python application and all original data untouched.

## What you import

One top-level backup ZIP, with any outer wrapper directory, ideally containing:

```text
app-backup/
  processed/
    MacGregor_Crane__job42/
      source_manifest.json
      retrieval_index.jsonl
      chunks.jsonl
      visual_evidence_index.jsonl
      ... other processed artifacts
    equipment_embedding_index/
      cargo_crane/
        <embedding-model>/
          metadata.json
          rows.jsonl
          vectors.f32
  input/
    MacGregor_Crane.pdf
    AD136TI.pdf
  output/
    macgregor-converted.zip    # Docling JSON + picture assets
  db/ or data/db/
    jobs.db
```

If the archived content comes from the Docker volume where `/data` contains `processed`, `input`, `output` and `db`, zip that entire volume directory. Paths can have an outer prefix. Do **not** change the source filenames. The app looks for `retrieval_index.jsonl` recursively, as well as eligible `visual_evidence_index.jsonl` and `equipment_embedding_index/**/rows.jsonl` with matching float32 vectors and metadata. It does not need the original SQLite database to search; the DB is retained as an archived file only. No Docling conversion, stage review, OCR, embedding regeneration or modifications to your archive are performed.

### After import

- **Library** shows manual count, indexed text/visual chunks, mapped vector rows, PDF/image counts, converted ZIPs and original DBs, and a list of searchable manuals.
- **Ask** searches locally from a technical question, alarm code, parameter or identifier. Lexical mode is available with no LLM/network. Optional hybrid mode sends the **query only** to the explicitly configured embedding endpoint and combines lexical/vector hits with reciprocal-rank fusion. It never silently changes to a different model.
- **Generate grounded answer** calls an explicitly chosen OpenAI-compatible LLM with the question and a bounded set of source excerpts. Answers carry [S1], [S2] etc.; click the evidence buttons to verify original pages. Lack of/invalid citations is flagged, not silently repaired.
- **View source** opens the precisely named original PDF at the source's one-based page number, if available, with pinch zoom, or the source text. Docling picture artifacts are read from the original converted ZIP by `source_manifest.json` + Docling `#/pictures/N` / PDF page provenance. Unreliable matches are not displayed as evidence.
- **Models** lets you enter local llama.cpp / KoboldCpp / vLLM etc. URLs or cloud OpenAI-compatible URLs plus API keys. `Connect & auto-detect models` requests `GET /v1/models`; pick the returned model or enter it manually.

## Model setup

**Local llama.cpp on the phone:** with llama-server running in Termux on `127.0.0.1:8080`, set answer URL `http://127.0.0.1:8080` and click **Connect & auto-detect models**. The default answer provider remains unconnected unless you configure it. For a separate LAN server, use its private IP: e.g. `http://192.168.68.55:8080`. Ensure its server listens on the correct interface and the phone can reach it. Nothing in this app starts llama.cpp for you.

**Cloud:** set provider URL, such as `https://api.groq.com/openai/v1`, enter API key and press auto-detect. The remote host must use HTTPS, and **only selected excerpts and the question** are sent when you explicitly request answer generation. No provider fallback.

**Hybrid embedding search:** imported `vectors.f32` are already built; nevertheless a **new query embedding** is needed for each question. The embedding server must use the *same exact model/weights and query prefix* originally used by your Docling Stage 3 pipeline. Set embedding URL ending `/embed` for the existing repo's `{inputs:[...]}` service, or an OpenAI-compatible `.../v1/embeddings` endpoint. Pick the model found in the ZIP metadata. Lexical mode needs no embedding endpoint. The LLM used to write answers is independent from the embedding model.

## Build

Minimum Android version: 8.0 (API 26). Target API 35 (Android 15). Jetpack Compose Material 3 UI, AGP 9.2.0, Gradle 9.4.1, compile SDK 36, Java 17, Kotlin Compose plugin 2.4.10. Dependency versions should be checked against the installed Android Studio version.

1. Open this folder as an Android Studio project with SDK 36, or use the GitHub Actions build workflow. **Installable upgrade APKs from GitHub require the one-time `SHIPRAG_SIGNING_PASSPHRASE` Actions secret** as described in [signing/README.md](signing/README.md). Without it, CI tests pass but intentionally do not publish another incompatible debug APK.
2. Build > Build Bundle(s) / APK(s) > Build APK(s), or run `gradle :app:assembleDebug` with **Gradle 9.4.1** after installing Android SDK 36.
3. Install the artifact named **ShipRAG-Android-permanently-signed-APK**. Every later signed build has the same signing certificate and a higher version code, so install it over the existing version without uninstalling or reimporting your ZIP.
4. Select your full backup ZIP with the Android document picker. Importing copies the expanded files into private app storage; free internal storage must accommodate both the existing ZIP and extracted data.

The included `scripts/generate_fixture.py` creates a tiny, **synthetic** RAG archive for checking the importer and source viewer; it is not a real ship manual. A sample can be generated with `python scripts/generate_fixture.py /path/to/test.zip` (requires only Python stdlib).

## Correctness boundaries

- The lexical ranker is a **mobile implementation** inspired by `app/retrieval.py` (FTS4 candidates, BM25-like scoring, literal identifier boosts); it is not a byte-for-byte copy of the latest Python v9.2 reranker. The hybrid path uses normalized float32 dot products and RRF. Final relevance and semantic answer correctness need to be benchmarked on your actual exported ZIP.
- Indexes must be present in the backup. `chunks.jsonl` alone is *not* accepted as a replacement for a built retrieval index.
- Machine vectors that do not match an indexed source chunk ID are not assigned to a different chunk. Invalid metadata/vector lengths are skipped. Missing or misaligned vector models never become automatic re-embedding jobs.
- Extracted visual summaries were generated by a model and may be wrong. The original picture/PDF is the verification reference. The app does not represent generated prose as a verified manual statement.
- PDF page numbering uses one-based `page_numbers` converted to zero-based PdfRenderer indexes. This is not automatically the same as printed page numbers in scanned books.
- The original database may include sensitive service tokens and metadata; it stays in app-private storage. Avoid copying the archive into shared folders once imported. Provider API keys typed into the app are encrypted with Android Keystore.
- Do not treat generated maintenance instructions or electrical safety procedures as independent authority; always verify critical steps against the cited original source and vessel procedures.
- There is currently no built-in model runtime or packaged LLM weights, no PDF text bounding-box highlight overlay, no voice input, and no Android APK attached to this source archive.

## Repo compatibility inspected

- `app/retrieval.py`: `retrieval_index.jsonl` and `chunks.jsonl`; indexed content/provenance fields.
- `app/hybrid_retrieval.py`: equipment `metadata.json`, `rows.jsonl`, `vectors.f32` float32 little-endian; model and query/document prefixes; machine scope.
- `app/visual_evidence.py`: `visual_evidence_index.jsonl` with `rag_eligible` and `#/pictures/N` Docling ref.
- `app/main.py` source page and picture routes; picture `image.uri` from the immutable converted ZIP.
- `app/rag_generation.py`: source-labeled and strict grounded answer policy.

No original repository file or ship-manual data is included in this source distribution.
## Preserve existing install across the one-time signing migration

Android forbids a direct upgrade from earlier GitHub-runner-signed builds with a different signing certificate. You can reimport your original ZIP once; after that, all stable-signed upgrades retain the library and settings. Optionally, advanced users with a computer and Android USB debugging can run `bash scripts/migrate-old-debug-install.sh /path/to/permanently-signed.apk` to back up and restore app-private library data during that one-time reinstall, without phone root. Keep its verified backup offline; encrypted API keys may need re-entering because uninstall clears Android Keystore keys. This migration path has not been tested on your phone; always keep the original ZIP as independent backup.
