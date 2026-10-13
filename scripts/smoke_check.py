#!/usr/bin/env python3
"""Pure-Python sanity check for a Docling-compatible backup ZIP."""
import json, struct, sys, zipfile
from pathlib import PurePosixPath

path = sys.argv[1] if len(sys.argv) > 1 else "ShipRAG-smoke-test.zip"
with zipfile.ZipFile(path) as z:
    names = z.namelist()
    for name in names:
        assert not name.startswith("/") and ".." not in PurePosixPath(name).parts, name
    keys = [n for n in names if n.endswith("retrieval_index.jsonl")]
    assert keys, "No retrieval indexes"
    chunks = [json.loads(line) for key in keys for line in z.read(key).decode().splitlines() if line.strip()]
    assert chunks and all(r.get("chunk_id") and r.get("text") for r in chunks)
    visual_indexes = [name for name in names if name.endswith("visual_evidence_index.jsonl")]
    visuals = [json.loads(line) for key in visual_indexes for line in z.read(key).decode().splitlines() if line.strip()]
    assert len(visuals) == 1 and all(row["rag_eligible"] and row["evidence_kind"] == "visual" and
                           row.get("doc_items") and row.get("text") for row in visuals)
    assert visuals[0]["doc_items"] == ["#/pictures/0"]
    from io import BytesIO
    with zipfile.ZipFile(BytesIO(z.read("output/Example_Crane_converted.zip"))) as docling:
        document = json.loads(docling.read("example_docling.json"))
        picture = document["pictures"][0]["image"]["uri"]
        assert picture == visuals[0]["artifact"] and picture in docling.namelist()
    pairs = {}
    for meta_name in [n for n in names if "equipment_embedding_index/" in n and n.endswith("metadata.json")]:
        parent = meta_name.rsplit("/",1)[0]
        metadata = json.loads(z.read(meta_name)); size = metadata["rows"]*metadata["dim"]*4
        vectors = z.read(parent + "/vectors.f32")
        rows = [json.loads(line) for line in z.read(parent + "/rows.jsonl").decode().splitlines() if line.strip()]
        assert size == len(vectors) and len(rows) == metadata["rows"]
        assert all(any(k["chunk_id"] == row["chunk_id"] for k in chunks) for row in rows)
        pairs[metadata["model"]] = len(rows)
    print("PASS",len(chunks),"text retrieval chunks;",len(visuals),"visual sources;",pairs,"embeddings;",len(names),"stored files")